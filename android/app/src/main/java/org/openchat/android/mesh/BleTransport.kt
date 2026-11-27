package org.openchat.android.mesh

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.ParcelUuid
import android.os.PowerManager
import android.util.Log
import java.util.UUID

/**
 * BLE link layer (docs/protocol-spec.md §1). Every device hosts the mesh GATT
 * service and also scans for and connects to others. Each connection becomes
 * one MeshEngine link. All engine calls happen on [handler]'s thread.
 */
@SuppressLint("MissingPermission")
class BleTransport(
    private val context: Context,
    private val handler: Handler,
    private val engine: () -> MeshEngine,
) : LinkWriter {
    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val TAG = "BleTransport"
        private const val MAX_CENTRAL_LINKS = BleConnectionScheduler.MAX_CENTRAL_LINKS
        private const val DEFAULT_WRITE = 20
        /** Chunks a link may hold back; a frame that would not fit is refused whole, never cut (§1). */
        private const val QUEUE_CAP = 2_000
        /** Consecutive write failures after which the link is given up: the stack has stopped taking data. */
        private const val WRITE_FAILURE_LIMIT = 40
        private const val CONNECT_TIMEOUT_MS = BleConnectionScheduler.CONNECT_TIMEOUT_MS
    }

    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val adapter get() = manager.adapter

    private var server: BluetoothGattServer? = null
    private var characteristic: BluetoothGattCharacteristic? = null
    private var running = false

    /** Outgoing queue per link: Android allows one GATT operation in flight at a time. */
    private class LinkQueue(var maxWrite: Int) {
        val chunks = ArrayDeque<ByteArray>()
        var queuedBytes = 0
        var busy = false
        var failures = 0
    }

    // Central role: we connected to them.
    private val centrals = HashMap<String, BluetoothGatt>()         // address -> gatt
    private val centralChars = HashMap<String, BluetoothGattCharacteristic>()
    // Peripheral role: they connected to us and subscribed.
    private val subscribers = HashMap<String, BluetoothDevice>()
    private val queues = HashMap<String, LinkQueue>()
    private val connecting = HashSet<String>()
    private val peripheralMtu = HashMap<String, Int>()
    private val discoveredDevices = LinkedHashMap<String, BluetoothDevice>()
    private val scheduler = BleConnectionScheduler()

    // Scan duty cycle (aligned with bitchat BLEScanDutyPolicy).
    private var scanning = false
    private var scanFailures = 0
    private var scanStartedAt = 0L
    private var lastScanStartAttemptAt = 0L
    private var lastTrafficAt = 0L
    private val scanTick = Runnable { scanCycle() }
    private val drainTick = Runnable { drainCandidates() }

    val isEnabled get() = adapter?.isEnabled == true

    fun start() {
        if (running || !isEnabled) return
        running = true
        openServer()
        startAdvertising()
        scheduleScan(0)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(scanTick)
        handler.removeCallbacks(drainTick)
        stopScanning()
        try { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) } catch (e: Exception) { }
        centrals.values.forEach { try { it.disconnect(); it.close() } catch (e: Exception) { } }
        centrals.keys.toList().forEach { linkDown(centralLink(it)) }
        centrals.clear(); centralChars.clear(); connecting.clear()
        subscribers.keys.toList().forEach { linkDown(peripheralLink(it)) }
        subscribers.clear()
        queues.clear()
        discoveredDevices.clear()
        scheduler.reset()
        scanStartedAt = 0L
        lastTrafficAt = 0L
        try { server?.close() } catch (e: Exception) { }
        server = null
    }

    override fun close(linkId: String) {
        if (!linkId.startsWith("c:")) return
        val address = linkId.substring(2)
        connecting.remove(address)
        centralChars.remove(address)
        val gatt = centrals.remove(address)
        if (gatt != null) {
            try { gatt.disconnect(); gatt.close() } catch (_: Exception) { }
            linkDown(linkId)
            drainCandidates()
        }
    }

    private fun centralLink(address: String) = "c:$address"
    private fun peripheralLink(address: String) = "p:$address"

    // ------------------------------------------------------------- peripheral role

    private fun openServer() {
        val s = manager.openGattServer(context, serverCallback) ?: return
        val ch = BluetoothGattCharacteristic(
            CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        ch.addDescriptor(BluetoothGattDescriptor(CCCD_UUID, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(ch)
        s.addService(service)
        server = s
        characteristic = ch
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) handler.post {
                peripheralMtu.remove(device.address)
                if (subscribers.remove(device.address) != null) linkDown(peripheralLink(device.address))
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            handler.post {
                val link = peripheralLink(device.address)
                peripheralMtu[device.address] = mtu - 3
                queues[link]?.maxWrite = mtu - 3
                if (subscribers.containsKey(device.address)) engine().onLinkMtu(link, mtu - 3)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (responseNeeded) server?.sendResponse(device, requestId, android.bluetooth.BluetoothGatt.GATT_SUCCESS, 0, null)
            val enable = value != null && value.isNotEmpty() && value[0].toInt() != 0
            handler.post {
                val link = peripheralLink(device.address)
                if (enable && !subscribers.containsKey(device.address)) {
                    subscribers[device.address] = device
                    queues[link] = LinkQueue(peripheralMtu[device.address] ?: DEFAULT_WRITE)
                    engine().onLinkUp(link, queues[link]!!.maxWrite)
                } else if (!enable && subscribers.remove(device.address) != null) {
                    linkDown(link)
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, ch: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            if (value == null || value.isEmpty()) return
            handler.post {
                val link = peripheralLink(device.address)
                if (!queues.containsKey(link)) {
                    // A central may write before subscribing; treat it as a link anyway.
                    queues[link] = LinkQueue(peripheralMtu[device.address] ?: DEFAULT_WRITE)
                    subscribers[device.address] = device
                    engine().onLinkUp(link, queues[link]!!.maxWrite)
                }
                lastTrafficAt = System.currentTimeMillis()
                engine().onBytes(link, value)
            }
        }

        /** A central finishing a prepared (long) write expects an answer, or it stalls the link. */
        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, ch: BluetoothGattCharacteristic) {
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, ByteArray(0))
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            handler.post { queues[peripheralLink(device.address)]?.let { it.busy = false; pump(peripheralLink(device.address)) } }
        }
    }

    private fun startAdvertising() {
        val advertiser = adapter?.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .setIncludeDeviceName(false)
            .build()
        advertiser.startAdvertising(settings, data, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) { Log.w(TAG, "advertise failed $errorCode") }
    }

    // ------------------------------------------------------------- central role

    private fun scheduleScan(delayMs: Long) {
        handler.removeCallbacks(scanTick)
        handler.postDelayed(scanTick, delayMs.coerceAtLeast(0L))
    }

    private fun scheduleDrain(delayMs: Long) {
        handler.removeCallbacks(drainTick)
        handler.postDelayed(drainTick, delayMs.coerceAtLeast(0L))
    }

    /**
     * One step of the scan cycle (aligned with bitchat `BLEScanDutyPolicy`):
     * - Continuous while sparse (0–2 links) or during recent mesh traffic, refreshing every 20 min
     *   to avoid Android's 30-minute continuous-scan downgrade.
     * - Duty-cycled (5 s on / 10 s off, or 3 s / 15 s when dense; tighter under system power-save).
     * - Enforces minimum spacing between scan starts to stay well within Android's 5 starts / 30 s cap.
     */
    private fun scanCycle() {
        if (!running) return
        val now = System.currentTimeMillis()
        val links = centrals.size + subscribers.size
        scheduler.prune(now)
        scheduler.updateRssiThreshold(links, centrals.size + connecting.size, now)
        val hasRecentTraffic = lastTrafficAt != 0L && now - lastTrafficAt <= ScanDutyCycle.RECENT_TRAFFIC_WINDOW_MS
        val lowPower = powerManager?.isPowerSaveMode == true

        if (!ScanDutyCycle.wanted(centrals.size, connecting.size, MAX_CENTRAL_LINKS)) {
            stopScanning()
            scheduleScan(ScanDutyCycle.offMs(links))
            return
        }

        when (val plan = ScanDutyCycle.plan(links, hasRecentTraffic, lowPower)) {
            is ScanDutyCycle.ScanPlan.Continuous -> {
                if (scanning) {
                    if (now - scanStartedAt >= ScanDutyCycle.CONTINUOUS_REFRESH_MS) {
                        stopScanning()
                        startScanWithRateLimit(now, ScanDutyCycle.CONTINUOUS_RECHECK_MS)
                    } else {
                        scheduleScan(ScanDutyCycle.CONTINUOUS_RECHECK_MS)
                    }
                } else {
                    startScanWithRateLimit(now, ScanDutyCycle.CONTINUOUS_RECHECK_MS)
                }
            }
            is ScanDutyCycle.ScanPlan.DutyCycle -> {
                if (scanning) {
                    stopScanning()
                    scheduleScan(plan.offMs)
                } else {
                    startScanWithRateLimit(now, plan.onMs)
                }
            }
        }
    }

    private fun startScanWithRateLimit(now: Long, nextDelayMs: Long) {
        val elapsed = now - lastScanStartAttemptAt
        if (lastScanStartAttemptAt != 0L && elapsed < ScanDutyCycle.MIN_SCAN_START_SPACING_MS) {
            scheduleScan(ScanDutyCycle.MIN_SCAN_START_SPACING_MS - elapsed)
            return
        }
        if (startScanning()) scheduleScan(nextDelayMs)
        else scheduleScan(ScanDutyCycle.retryMs(++scanFailures))
    }

    private fun startScanning(): Boolean {
        val scanner = adapter?.bluetoothLeScanner ?: return false
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
        lastScanStartAttemptAt = System.currentTimeMillis()
        return try {
            scanner.startScan(listOf(filter), settings, scanCallback)
            scanning = true
            scanStartedAt = lastScanStartAttemptAt
            true
        } catch (e: Exception) {
            Log.w(TAG, "scan start failed: $e")
            false
        }
    }

    private fun stopScanning() {
        if (!scanning) return
        scanning = false
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (e: Exception) { }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val connectable = Build.VERSION.SDK_INT < 26 || result.isConnectable
            handler.post { scanFailures = 0; maybeConnect(result.device, result.rssi, connectable) }
        }
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            handler.post {
                scanFailures = 0
                results.forEach {
                    val connectable = Build.VERSION.SDK_INT < 26 || it.isConnectable
                    maybeConnect(it.device, it.rssi, connectable)
                }
            }
        }
        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed $errorCode")
            if (errorCode == SCAN_FAILED_ALREADY_STARTED) return // the stack is scanning for us after all
            // The start did not take (registration failed, too many starts, hardware busy): try again later.
            handler.post { scanning = false; if (running) scheduleScan(ScanDutyCycle.retryMs(++scanFailures)) }
        }
    }

    private fun rememberDevice(device: BluetoothDevice) {
        discoveredDevices[device.address] = device
        while (discoveredDevices.size > BleConnectionScheduler.CANDIDATE_CAP * 2) {
            discoveredDevices.remove(discoveredDevices.keys.first())
        }
    }

    private fun maybeConnect(device: BluetoothDevice, rssi: Int, isConnectable: Boolean) {
        if (!running) return
        val address = device.address
        if (subscribers.containsKey(address)) return
        val now = System.currentTimeMillis()
        val totalLinks = centrals.size + subscribers.size
        val candidate = BleConnectionScheduler.Candidate(address, rssi, isConnectable, now)
        when (val decision = scheduler.onDiscovery(
            candidate = candidate,
            totalLinks = totalLinks,
            centralLinksOrConnecting = centrals.size + connecting.size,
            isConnected = centrals.containsKey(address),
            isConnecting = connecting.contains(address),
            nowMs = now,
        )) {
            is BleConnectionScheduler.DiscoveryDecision.Ignore -> Unit
            is BleConnectionScheduler.DiscoveryDecision.Queued -> rememberDevice(device)
            is BleConnectionScheduler.DiscoveryDecision.ScheduleRetry -> {
                rememberDevice(device)
                scheduleDrain(decision.delayMs)
            }
            is BleConnectionScheduler.DiscoveryDecision.ConnectNow -> {
                rememberDevice(device)
                connectDevice(device, now)
            }
        }
    }

    private fun drainCandidates() {
        if (!running) return
        val now = System.currentTimeMillis()
        val totalLinks = centrals.size + subscribers.size
        scheduler.updateRssiThreshold(totalLinks, centrals.size + connecting.size, now)
        when (val next = scheduler.nextCandidate(
            centralLinksOrConnecting = centrals.size + connecting.size,
            isAlreadyActive = { addr -> centrals.containsKey(addr) || connecting.contains(addr) || subscribers.containsKey(addr) },
            nowMs = now,
        )) {
            is BleConnectionScheduler.QueueDecision.None -> Unit
            is BleConnectionScheduler.QueueDecision.RetryAfter -> scheduleDrain(next.delayMs)
            is BleConnectionScheduler.QueueDecision.Connect -> {
                val device = discoveredDevices[next.candidate.address]
                if (device != null) {
                    connectDevice(device, now)
                    if (scheduler.candidateCount > 0 && centrals.size + connecting.size < MAX_CENTRAL_LINKS) {
                        scheduleDrain(BleConnectionScheduler.CONNECT_RATE_LIMIT_MS + 50L)
                    }
                } else if (scheduler.candidateCount > 0) {
                    scheduleDrain(50L)
                }
            }
        }
    }

    private fun connectDevice(device: BluetoothDevice, now: Long) {
        val address = device.address
        if (centrals.containsKey(address) || connecting.contains(address) || subscribers.containsKey(address)) return
        if (centrals.size + connecting.size >= MAX_CENTRAL_LINKS) return
        scheduler.recordConnectionAttempt(address, now)
        connecting.add(address)
        val gatt = if (Build.VERSION.SDK_INT >= 23) device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        else device.connectGatt(context, false, gattCallback)
        if (gatt == null) {
            connecting.remove(address)
            scheduler.recordConnectionFailure(address)
            scheduleDrain(BleConnectionScheduler.CONNECT_RATE_LIMIT_MS)
            return
        }
        handler.postDelayed({
            // Still not ready (MTU, discovery or the CCCD write never answered): forget every trace of
            // the attempt, record timeout backoff, and try the next candidate.
            if (connecting.remove(address)) {
                scheduler.recordConnectionTimeout(address, System.currentTimeMillis())
                centrals.remove(address); centralChars.remove(address); queues.remove(centralLink(address))
                try { gatt.disconnect(); gatt.close() } catch (e: Exception) { }
                drainCandidates()
            }
        }, CONNECT_TIMEOUT_MS)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = gatt.device.address
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                handler.post { centrals[address] = gatt }
                if (!gatt.requestMtu(517)) gatt.discoverServices()
            } else {
                handler.post {
                    val now = System.currentTimeMillis()
                    val wasConnecting = connecting.remove(address)
                    centralChars.remove(address)
                    val wasConnected = centrals.remove(address) != null
                    if (wasConnected) {
                        if (wasConnecting) scheduler.recordConnectionFailure(address)
                        else scheduler.recordDisconnect(address, now)
                        linkDown(centralLink(address))
                    } else if (wasConnecting) {
                        scheduler.recordConnectionFailure(address)
                    }
                    drainCandidates()
                    val links = centrals.size + subscribers.size
                    if (running && !scanning && links <= ScanDutyCycle.SPARSE_CONTINUOUS_MAX_LINKS) {
                        scheduleScan(100L)
                    }
                }
                try { gatt.close() } catch (e: Exception) { }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            handler.post { queues.getOrPut(centralLink(gatt.device.address)) { LinkQueue(DEFAULT_WRITE) }.maxWrite = mtu - 3 }
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val ch = gatt.getService(SERVICE_UUID)?.getCharacteristic(CHARACTERISTIC_UUID)
            if (ch == null) { gatt.disconnect(); return }
            gatt.setCharacteristicNotification(ch, true)
            val cccd = ch.getDescriptor(CCCD_UUID)
            if (cccd != null) {
                if (Build.VERSION.SDK_INT >= 33) gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                else @Suppress("DEPRECATION") run { cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; gatt.writeDescriptor(cccd) }
            } else {
                handler.post { centralReady(gatt, ch) }
            }
            handler.post { centralChars[gatt.device.address] = ch }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val ch = descriptor.characteristic
            handler.post { centralReady(gatt, ch) }
        }

        @Deprecated("API < 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") val value = ch.value ?: return
            val copy = value.copyOf()
            handler.post { lastTrafficAt = System.currentTimeMillis(); engine().onBytes(centralLink(gatt.device.address), copy) }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            handler.post { lastTrafficAt = System.currentTimeMillis(); engine().onBytes(centralLink(gatt.device.address), value) }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            val link = centralLink(gatt.device.address)
            handler.post { queues[link]?.let { it.busy = false; pump(link) } }
        }
    }

    private fun centralReady(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        val address = gatt.device.address
        if (!connecting.remove(address)) return
        scheduler.recordConnectionSuccess(address)
        centralChars[address] = ch
        val link = centralLink(address)
        val q = queues.getOrPut(link) { LinkQueue(DEFAULT_WRITE) }
        engine().onLinkUp(link, q.maxWrite)
        drainCandidates()
    }

    private fun linkDown(link: String) {
        queues.remove(link)
        engine().onLinkDown(link)
    }

    // ------------------------------------------------------------- writing

    /** Called by MeshEngine on the handler thread. Frames larger than the link limit go out in pieces. */
    override fun write(linkId: String, bytes: ByteArray) {
        val q = queues[linkId] ?: return
        lastTrafficAt = System.currentTimeMillis()
        val step = maxOf(DEFAULT_WRITE, q.maxWrite)
        val pieces = (bytes.size + step - 1) / step
        // Dropping chunks from a full queue would cut a frame in half and leave the far end's framer
        // hunting for the next header inside our tail. A frame goes out whole or not at all.
        if (q.chunks.size + pieces > QUEUE_CAP) { Log.w(TAG, "queue full on $linkId, frame of ${bytes.size} bytes dropped"); return }
        var i = 0
        while (i < bytes.size) {
            q.chunks.addLast(bytes.copyOfRange(i, minOf(i + step, bytes.size)))
            i += step
        }
        q.queuedBytes += bytes.size
        pump(linkId)
    }

    /** Bytes still waiting on a link, so the engine can pace fragments to what the radio takes. */
    override fun pending(linkId: String): Int = queues[linkId]?.queuedBytes ?: 0

    private fun pump(linkId: String) {
        val q = queues[linkId] ?: return
        if (q.busy) return
        val chunk = q.chunks.removeFirstOrNull() ?: return
        val address = linkId.substring(2)
        val ok = if (linkId.startsWith("c:")) {
            val gatt = centrals[address]; val ch = centralChars[address]
            if (gatt == null || ch == null) false
            else if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeCharacteristic(ch, chunk, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else @Suppress("DEPRECATION") run {
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                ch.value = chunk
                gatt.writeCharacteristic(ch)
            }
        } else {
            val device = subscribers[address]; val ch = characteristic; val s = server
            if (device == null || ch == null || s == null) false
            else if (Build.VERSION.SDK_INT >= 33) {
                s.notifyCharacteristicChanged(device, ch, false, chunk) == android.bluetooth.BluetoothStatusCodes.SUCCESS
            } else @Suppress("DEPRECATION") run {
                ch.value = chunk
                s.notifyCharacteristicChanged(device, ch, false)
            }
        }
        if (ok) {
            q.busy = true
            q.failures = 0
            q.queuedBytes -= chunk.size
            // Safety net in case the stack never reports completion.
            handler.postDelayed({ if (q.busy) { q.busy = false; pump(linkId) } }, 300)
        } else {
            q.chunks.addFirst(chunk)
            if (++q.failures >= WRITE_FAILURE_LIMIT) {
                // The stack refuses everything: the connection is gone in all but name. Drop the link
                // rather than retry every 50 ms until the phone dies.
                Log.w(TAG, "link $linkId stopped taking writes, dropping it")
                q.chunks.clear(); q.queuedBytes = 0
                if (linkId.startsWith("c:")) {
                    centralChars.remove(address)
                    centrals.remove(address)?.let { g -> try { g.disconnect(); g.close() } catch (e: Exception) { } }
                } else subscribers.remove(address)
                linkDown(linkId)
                return
            }
            handler.postDelayed({ pump(linkId) }, 50)
        }
    }
}
