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
        private const val MAX_CENTRAL_LINKS = 6
        private const val RSSI_FLOOR = -90
        private const val DEFAULT_WRITE = 20
        /** Chunks a link may hold back; a frame that would not fit is refused whole, never cut (§1). */
        private const val QUEUE_CAP = 2_000
        /** Consecutive write failures after which the link is given up: the stack has stopped taking data. */
        private const val WRITE_FAILURE_LIMIT = 40
        private const val CONNECT_TIMEOUT_MS = 15_000L
    }

    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
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
    private val lastAttempt = HashMap<String, Long>()

    // Scan duty cycle (§11): on for a short window, off for a longer one; see ScanDutyCycle.
    private var scanning = false
    private var scanFailures = 0
    private val scanTick = Runnable { scanCycle() }

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
        stopScanning()
        try { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) } catch (e: Exception) { }
        centrals.values.forEach { try { it.disconnect(); it.close() } catch (e: Exception) { } }
        centrals.keys.toList().forEach { linkDown(centralLink(it)) }
        centrals.clear(); centralChars.clear(); connecting.clear()
        subscribers.keys.toList().forEach { linkDown(peripheralLink(it)) }
        subscribers.clear()
        queues.clear()
        try { server?.close() } catch (e: Exception) { }
        server = null
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
        handler.postDelayed(scanTick, delayMs)
    }

    /**
     * One step of the duty cycle: scanning stops after its window and rests for the off window;
     * a rest ends with a new scan unless the central table is full, in which case the rest goes
     * on. Android allows five scan starts per 30 s; this cycle uses at most two.
     */
    private fun scanCycle() {
        if (!running) return
        val links = centrals.size + subscribers.size
        if (scanning) { stopScanning(); scheduleScan(ScanDutyCycle.offMs(links)); return }
        if (!ScanDutyCycle.wanted(centrals.size, connecting.size, MAX_CENTRAL_LINKS)) { scheduleScan(ScanDutyCycle.offMs(links)); return }
        if (startScanning()) scheduleScan(ScanDutyCycle.onMs(links))
        else scheduleScan(ScanDutyCycle.retryMs(++scanFailures))
    }

    private fun startScanning(): Boolean {
        val scanner = adapter?.bluetoothLeScanner ?: return false
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build()
        return try {
            scanner.startScan(listOf(filter), settings, scanCallback)
            scanning = true
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
            handler.post { scanFailures = 0; maybeConnect(result.device, result.rssi) }
        }
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            handler.post { scanFailures = 0; results.forEach { maybeConnect(it.device, it.rssi) } }
        }
        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed $errorCode")
            if (errorCode == SCAN_FAILED_ALREADY_STARTED) return // the stack is scanning for us after all
            // The start did not take (registration failed, too many starts, hardware busy): try again later.
            handler.post { scanning = false; if (running) scheduleScan(ScanDutyCycle.retryMs(++scanFailures)) }
        }
    }

    private fun maybeConnect(device: BluetoothDevice, rssi: Int) {
        if (!running) return
        val address = device.address
        if (centrals.containsKey(address) || connecting.contains(address) || subscribers.containsKey(address)) return
        if (centrals.size + connecting.size >= MAX_CENTRAL_LINKS || rssi < RSSI_FLOOR) return
        val now = System.currentTimeMillis()
        if (now - (lastAttempt[address] ?: 0) < 5_000) return
        lastAttempt[address] = now
        connecting.add(address)
        val gatt = if (Build.VERSION.SDK_INT >= 23) device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        else device.connectGatt(context, false, gattCallback)
        if (gatt == null) connecting.remove(address)
        handler.postDelayed({
            // Still not ready (MTU, discovery or the CCCD write never answered): forget every trace of
            // the attempt, or the address stays "connected" in our tables and is never tried again.
            if (connecting.remove(address)) {
                centrals.remove(address); centralChars.remove(address); queues.remove(centralLink(address))
                try { gatt?.disconnect(); gatt?.close() } catch (e: Exception) { }
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
                    connecting.remove(address)
                    centralChars.remove(address)
                    if (centrals.remove(address) != null) linkDown(centralLink(address))
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
            handler.post { engine().onBytes(centralLink(gatt.device.address), copy) }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            handler.post { engine().onBytes(centralLink(gatt.device.address), value) }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            val link = centralLink(gatt.device.address)
            handler.post { queues[link]?.let { it.busy = false; pump(link) } }
        }
    }

    private fun centralReady(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        val address = gatt.device.address
        if (!connecting.remove(address)) return
        centralChars[address] = ch
        val link = centralLink(address)
        val q = queues.getOrPut(link) { LinkQueue(DEFAULT_WRITE) }
        engine().onLinkUp(link, q.maxWrite)
    }

    private fun linkDown(link: String) {
        queues.remove(link)
        engine().onLinkDown(link)
    }

    // ------------------------------------------------------------- writing

    /** Called by MeshEngine on the handler thread. Frames larger than the link limit go out in pieces. */
    override fun write(linkId: String, bytes: ByteArray) {
        val q = queues[linkId] ?: return
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
