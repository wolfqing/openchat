package org.openchat.android.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Process
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import org.openchat.android.R
import org.openchat.android.nostr.NetRoute
import org.torproject.jni.TorService
import org.torproject.jni.TorStatus
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Memory-only, privacy-safe diagnostic ring buffer for Tor / network / transport troubleshooting.
 * Never logs chat messages, contacts, keys, or fingerprints; cleared immediately on panic wipe.
 */
object DiagLog {
    private const val MAX_LINES = 600
    private val lock = Any()
    private val lines = ArrayDeque<String>(MAX_LINES)
    private val stampFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun record(tag: String, message: String) {
        val clean = message.replace('\r', ' ').replace('\n', ' ').trim()
        if (clean.isEmpty()) return
        synchronized(lock) {
            val ts = stampFmt.format(Date())
            if (lines.size >= MAX_LINES) lines.removeFirst()
            lines.addLast("$ts [$tag] $clean")
        }
    }

    fun clear() {
        synchronized(lock) { lines.clear() }
    }

    fun snapshot(): List<String> = synchronized(lock) { lines.toList() }

    fun buildReport(context: Context): String {
        val sb = StringBuilder(8192)
        val pkgInfo = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val verName = pkgInfo?.versionName ?: "?"
        val verCode = pkgInfo?.let { PackageInfoCompat.getLongVersionCode(it) } ?: -1L
        val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date())

        sb.appendLine("=== OpenChat Diagnostic Log ===")
        sb.appendLine("Time: $nowIso")
        sb.appendLine("App: ${context.packageName} $verName ($verCode)")
        sb.appendLine("Device: ${Build.BRAND} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine()

        val geo = ChatRepository.geo.value
        val mesh = ChatRepository.mesh.value
        sb.appendLine("--- State ---")
        sb.appendLine("Tor: state=${geo.tor} progress=${geo.torProgress}% stalled=${geo.torStalled} detail=${geo.torDetail}")
        sb.appendLine("TorService: status=${TorStatus.current} staticSocksPort=${TorService.socksPort} httpTunnelPort=${TorService.httpTunnelPort}")
        sb.appendLine("NetRoute: torWanted=${NetRoute.torWanted} socksPort=${NetRoute.socksPort} ready=${NetRoute.ready} gen=${NetRoute.generation}")
        sb.appendLine("ManualProxy: ${geo.torProxy.ifEmpty { "(none)" }}")
        sb.appendLine("Nostr: relaysConnected=${geo.relaysConnected} joined=${geo.joined.size} connectedChannels=${geo.connectedChannels.size}")
        sb.appendLine("Mesh: btOn=${mesh.bluetoothOn} running=${mesh.running} links=${mesh.links}")
        sb.appendLine()

        sb.appendLine("--- Network & System Proxy ---")
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val net = cm?.activeNetwork
            val caps = net?.let { cm.getNetworkCapabilities(it) }
            val lp = net?.let { cm.getLinkProperties(it) }
            val transports = buildList {
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add("WIFI")
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("CELLULAR")
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) add("VPN")
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) add("ETHERNET")
            }
            val flags = buildList {
                if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) add("INTERNET")
                if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) add("VALIDATED")
                if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) == true) add("NOT_VPN")
            }
            @Suppress("DEPRECATION")
            val defProxy = runCatching { cm?.defaultProxy }.getOrNull()
            val linkProxy = lp?.httpProxy
            sb.appendLine("ActiveNetwork: ${if (net != null) "present" else "null"} transports=$transports caps=$flags")
            sb.appendLine("Interface: ${lp?.interfaceName ?: "(none)"} dnsCount=${lp?.dnsServers?.size ?: 0} linkAddrCount=${lp?.linkAddresses?.size ?: 0}")
            sb.appendLine("defaultProxy: ${defProxy?.let { "${it.host}:${it.port} pac=${it.pacFileUrl}" } ?: "(none)"}")
            sb.appendLine("linkHttpProxy: ${linkProxy?.let { "${it.host}:${it.port} pac=${it.pacFileUrl}" } ?: "(none)"}")
            sb.appendLine("jvmProxy: http=${System.getProperty("http.proxyHost")}:${System.getProperty("http.proxyPort")} socks=${System.getProperty("socksProxyHost")}:${System.getProperty("socksProxyPort")}")
        }.onFailure { e ->
            sb.appendLine("Network inspect error: ${e.javaClass.simpleName}: ${e.message}")
        }
        sb.appendLine()

        sb.appendLine("--- Tor Config Files ---")
        appendFileSnippet(sb, "torrc", runCatching { TorService.getTorrc(context) }.getOrNull())
        appendFileSnippet(sb, "torrc-defaults", runCatching { TorService.getDefaultsTorrc(context) }.getOrNull())
        runCatching {
            val dataDir = File(context.getDir("TorService", Context.MODE_PRIVATE), "data")
            val entries = dataDir.listFiles()?.sortedBy { it.name }?.joinToString(", ") { "${it.name}(${it.length()}B)" } ?: "(missing)"
            sb.appendLine("TorService/data: $entries")
        }
        sb.appendLine()

        sb.appendLine("--- In-App Diagnostic Events (${snapshot().size}) ---")
        val recorded = snapshot()
        if (recorded.isEmpty()) sb.appendLine("(none yet)")
        else recorded.forEach { sb.appendLine(it) }
        sb.appendLine()

        sb.appendLine("--- Process Logcat (own PID ${Process.myPid()}, last 250 lines) ---")
        val logcatLines = readOwnLogcat(250)
        if (logcatLines.isEmpty()) sb.appendLine("(unavailable)")
        else logcatLines.forEach { sb.appendLine(it) }

        return sb.toString()
    }

    private fun appendFileSnippet(sb: StringBuilder, label: String, file: File?) {
        if (file == null || !file.isFile) {
            sb.appendLine("$label: (not present)")
            return
        }
        val text = runCatching { file.readText(Charsets.UTF_8).trim() }.getOrElse { "(read error: ${it.message})" }
        sb.appendLine("$label (${file.length()}B):")
        text.lines().take(30).forEach { sb.appendLine("  $it") }
    }

    private val NOISY_LOGCAT = Regex("""(DecorView|BLASTBufferQueue|HwViewRootImpl|InputEvent|IAwareSdk|HiTouch|InsetsController)""")

    private fun readOwnLogcat(maxLines: Int): List<String> = runCatching {
        val pid = Process.myPid().toString()
        val proc = ProcessBuilder("logcat", "-d", "-v", "time", "--pid=$pid")
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().useLines { seq ->
            val buf = ArrayDeque<String>(maxLines)
            for (line in seq) {
                if (NOISY_LOGCAT.containsMatchIn(line)) continue
                if (buf.size >= maxLines) buf.removeFirst()
                buf.addLast(line)
            }
            buf.toList()
        }
        proc.destroy()
        out
    }.getOrDefault(emptyList())

    /** Writes the diagnostic report to `cache/share/` and opens Android's system share sheet. */
    fun shareReport(context: Context) {
        runCatching {
            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            dir.listFiles { f -> f.name.startsWith("openchat-diag-") }?.forEach { it.delete() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val out = File(dir, "openchat-diag-$stamp.txt")
            out.writeText(buildReport(context), Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", out)
            val send = Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "OpenChat Diagnostic Log ($stamp)")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(send, context.getString(R.string.diag_export_title)))
        }.onFailure { e ->
            Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** Copies the diagnostic report directly to the clipboard for instant pasting. */
    fun copyReport(context: Context) {
        val text = buildReport(context)
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText("openchat-diag-log", text))
        Toast.makeText(context, R.string.diag_copied, Toast.LENGTH_SHORT).show()
    }
}
