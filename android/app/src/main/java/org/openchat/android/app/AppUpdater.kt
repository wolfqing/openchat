package org.openchat.android.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Call
import okhttp3.Request
import org.json.JSONArray
import org.openchat.android.nostr.NetRoute
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Discovers, downloads and verifies newer openchat APKs from GitHub Releases.
 *
 * Security invariants:
 * 1. Every request uses [NetRoute.client]: when Tor is enabled, nothing leaves
 *    the phone until Tor's SOCKS port is up (fail-closed), and any route change
 *    aborts an in-flight transfer.
 * 2. Downloaded APKs must match BOTH the release's `SHA256SUMS` file AND the
 *    currently installed app's signing certificate before the system installer
 *    is ever opened.
 */
object AppUpdater {
    private const val RELEASES_API = "https://api.github.com/repos/wolfqing/openchat/releases?per_page=5"
    private const val PREFS = "update"
    private const val KEY_AUTO_CHECK = "auto_check"
    private const val MAX_APK_BYTES = 80L * 1024 * 1024
    private const val MAX_SUMS_BYTES = 64 * 1024

    data class ReleaseInfo(
        val tagName: String,
        val versionName: String,
        val title: String,
        val summary: String,
        val apkName: String,
        val apkUrl: String,
        val apkSizeBytes: Long,
        val sumsUrl: String,
        val prerelease: Boolean,
    )

    enum class UpdateError {
        TOR_NOT_READY,
        NETWORK,
        CHECKSUM_MISSING,
        HASH_MISMATCH,
        SIGNATURE_MISMATCH,
        DOWNGRADE_BLOCKED,
        INSTALL_FAILED,
    }

    sealed interface UpdateState {
        data object Idle : UpdateState
        data object Checking : UpdateState
        data class UpToDate(val currentVersion: String) : UpdateState
        data class Available(val release: ReleaseInfo) : UpdateState
        data class Downloading(val release: ReleaseInfo, val bytesRead: Long, val totalBytes: Long) : UpdateState
        data class Verifying(val release: ReleaseInfo) : UpdateState
        data class ReadyToInstall(val release: ReleaseInfo, val apkFile: File) : UpdateState
        data class Error(val error: UpdateError, val release: ReleaseInfo? = null) : UpdateState
    }

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    @Volatile private var activeToken = 0L
    @Volatile private var activeCall: Call? = null
    @Volatile private var autoCheckedThisRun = false
    @Volatile private var waitingForRouteContext: Context? = null
    @Volatile private var pendingInstallFile: File? = null

    init {
        NetRoute.onChange {
            // Abort any active transfer if the network route changes underneath us (e.g. Tor toggled on).
            activeCall?.cancel()
            val ctx = waitingForRouteContext
            if (ctx != null && !autoCheckedThisRun && NetRoute.ready && autoCheck(ctx)) {
                waitingForRouteContext = null
                autoCheckedThisRun = true
                startCheck(ctx, silent = true)
            }
        }
    }

    fun autoCheck(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_CHECK, false)

    fun setAutoCheck(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_AUTO_CHECK, enabled).apply()
        if (enabled) maybeAutoCheck(context.applicationContext) else waitingForRouteContext = null
    }

    /** Runs at most once per process launch when the user opted into launch checks. */
    fun maybeAutoCheck(context: Context) {
        val appCtx = context.applicationContext
        if (autoCheckedThisRun || !autoCheck(appCtx)) return
        if (NetRoute.ready) {
            autoCheckedThisRun = true
            waitingForRouteContext = null
            startCheck(appCtx, silent = true)
        } else {
            waitingForRouteContext = appCtx
        }
    }

    fun checkNow(context: Context) {
        startCheck(context.applicationContext, silent = false)
    }

    private fun startCheck(appContext: Context, silent: Boolean) {
        val cur = _state.value
        if (cur is UpdateState.Checking || cur is UpdateState.Downloading || cur is UpdateState.Verifying) return
        val routeGen = NetRoute.generation
        val baseClient = NetRoute.client()
        if (baseClient == null) {
            if (!silent) {
                _state.value = UpdateState.Error(if (NetRoute.torWanted) UpdateError.TOR_NOT_READY else UpdateError.NETWORK)
            }
            return
        }
        val token = ++activeToken
        if (!silent) _state.value = UpdateState.Checking

        val currentVersion = installedVersionName(appContext)
        Thread {
            try {
                val http = baseClient.newBuilder()
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()
                val req = Request.Builder()
                    .url(RELEASES_API)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "openchat-android/$currentVersion")
                    .build()
                val call = http.newCall(req)
                activeCall = call
                val body = call.execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    resp.body.string()
                }
                if (token != activeToken || NetRoute.generation != routeGen) return@Thread

                val newer = parseReleasesJson(body, currentVersion)
                if (newer == null) {
                    if (!silent) _state.value = UpdateState.UpToDate(currentVersion)
                    return@Thread
                }

                val existing = cachedVerifiedApk(appContext, newer)
                _state.value = if (existing != null) {
                    UpdateState.ReadyToInstall(newer, existing)
                } else {
                    UpdateState.Available(newer)
                }
            } catch (_: Exception) {
                if (token != activeToken) return@Thread
                if (!silent) {
                    val err = if (NetRoute.torWanted && !NetRoute.ready) UpdateError.TOR_NOT_READY else UpdateError.NETWORK
                    _state.value = UpdateState.Error(err)
                }
            } finally {
                if (token == activeToken) activeCall = null
            }
        }.start()
    }

    fun download(context: Context, release: ReleaseInfo) {
        val appContext = context.applicationContext
        val cur = _state.value
        if (cur is UpdateState.Downloading || cur is UpdateState.Verifying) return
        val routeGen = NetRoute.generation
        val baseClient = NetRoute.client()
        if (baseClient == null) {
            _state.value = UpdateState.Error(
                if (NetRoute.torWanted) UpdateError.TOR_NOT_READY else UpdateError.NETWORK,
                release,
            )
            return
        }

        val token = ++activeToken
        _state.value = UpdateState.Downloading(release, 0L, release.apkSizeBytes)

        Thread {
            val dir = updateDir(appContext).apply { mkdirs() }
            val partFile = File(dir, "${release.apkName}.part")
            try {
                val http = baseClient.newBuilder()
                    .readTimeout(45, TimeUnit.SECONDS)
                    .build()

                // Step 1: Fetch SHA256SUMS first so an untrusted or corrupted stream fails fast.
                val sumsCall = http.newCall(
                    Request.Builder()
                        .url(release.sumsUrl)
                        .header("User-Agent", "openchat-android")
                        .build(),
                )
                activeCall = sumsCall
                val sumsText = sumsCall.execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val bytes = resp.body.byteStream().readNBytesCompat(MAX_SUMS_BYTES)
                    bytes.toString(Charsets.UTF_8)
                }
                if (token != activeToken || NetRoute.generation != routeGen) {
                    partFile.delete()
                    return@Thread
                }

                val expectedSha = parseSha256Sums(sumsText, release.apkName)
                if (expectedSha == null) {
                    _state.value = UpdateState.Error(UpdateError.CHECKSUM_MISSING, release)
                    return@Thread
                }

                // Reuse an already-downloaded file if it already passes both SHA-256 and cert checks.
                val targetFile = File(dir, release.apkName)
                if (targetFile.isFile && sha256Hex(targetFile) == expectedSha && verifyApkArchive(appContext, targetFile) == null) {
                    saveVerifiedSha(appContext, release.apkName, expectedSha)
                    _state.value = UpdateState.ReadyToInstall(release, targetFile)
                    return@Thread
                }

                // Step 2: Stream the APK while hashing it incrementally.
                val apkCall = http.newCall(
                    Request.Builder()
                        .url(release.apkUrl)
                        .header("User-Agent", "openchat-android")
                        .build(),
                )
                activeCall = apkCall
                val actualSha = apkCall.execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val body = resp.body
                    val total = body.contentLength().takeIf { it > 0L } ?: release.apkSizeBytes
                    if (total > MAX_APK_BYTES) throw IOException("APK too large: $total")
                    val digest = MessageDigest.getInstance("SHA-256")
                    var readTotal = 0L
                    var lastEmitMs = 0L
                    body.byteStream().use { input ->
                        FileOutputStream(partFile).use { out ->
                            val buf = ByteArray(16 * 1024)
                            while (true) {
                                if (token != activeToken || NetRoute.generation != routeGen) {
                                    throw IOException("Cancelled")
                                }
                                val n = input.read(buf)
                                if (n < 0) break
                                readTotal += n
                                if (readTotal > MAX_APK_BYTES) throw IOException("APK exceeded max size")
                                digest.update(buf, 0, n)
                                out.write(buf, 0, n)
                                val now = System.currentTimeMillis()
                                if (now - lastEmitMs >= 100L || readTotal == total) {
                                    lastEmitMs = now
                                    _state.value = UpdateState.Downloading(release, readTotal, total)
                                }
                            }
                            out.flush()
                            try { out.fd.sync() } catch (_: Exception) { }
                        }
                    }
                    digest.digest().toHexLower()
                }

                if (token != activeToken || NetRoute.generation != routeGen) {
                    partFile.delete()
                    return@Thread
                }

                _state.value = UpdateState.Verifying(release)

                // Step 3: Verify SHA-256 against SHA256SUMS.
                if (actualSha != expectedSha) {
                    partFile.delete()
                    _state.value = UpdateState.Error(UpdateError.HASH_MISMATCH, release)
                    return@Thread
                }

                // Step 4: Verify package name, non-downgrade versionCode, and signing certificate match.
                val sigErr = verifyApkArchive(appContext, partFile)
                if (sigErr != null) {
                    partFile.delete()
                    _state.value = UpdateState.Error(sigErr, release)
                    return@Thread
                }

                // Clean older cached APKs before moving the verified APK into place.
                dir.listFiles()?.forEach { f ->
                    if (f != partFile && f != targetFile) f.delete()
                }
                if (targetFile.exists()) targetFile.delete()
                if (!partFile.renameTo(targetFile)) {
                    partFile.copyTo(targetFile, overwrite = true)
                    partFile.delete()
                }
                saveVerifiedSha(appContext, release.apkName, expectedSha)
                _state.value = UpdateState.ReadyToInstall(release, targetFile)
            } catch (_: Exception) {
                partFile.delete()
                if (token != activeToken) return@Thread
                val err = if (NetRoute.torWanted && !NetRoute.ready) UpdateError.TOR_NOT_READY else UpdateError.NETWORK
                _state.value = UpdateState.Error(err, release)
            } finally {
                if (token == activeToken) activeCall = null
            }
        }.start()
    }

    fun cancel() {
        val prev = _state.value
        activeToken++
        activeCall?.cancel()
        activeCall = null
        _state.value = when (prev) {
            is UpdateState.Downloading -> UpdateState.Available(prev.release)
            is UpdateState.Verifying -> UpdateState.Available(prev.release)
            is UpdateState.Checking -> UpdateState.Idle
            else -> prev
        }
    }

    /** Opens the Android package installer, or asks for unknown-sources permission first on Android 8+. */
    fun install(activity: Activity, apkFile: File) {
        if (!apkFile.isFile) {
            _state.value = UpdateState.Error(UpdateError.INSTALL_FAILED)
            return
        }
        val pm = activity.packageManager
        if (!pm.canRequestPackageInstalls()) {
            pendingInstallFile = apkFile
            try {
                val settingsIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}"),
                )
                activity.startActivity(settingsIntent)
                return
            } catch (_: Exception) {
                pendingInstallFile = null
            }
        }
        launchPackageInstaller(activity, apkFile)
    }

    /** Resumes an install automatically once the user grants "Install unknown apps" and returns. */
    fun onHostResume(activity: Activity) {
        val pending = pendingInstallFile ?: return
        pendingInstallFile = null
        if (pending.isFile && activity.packageManager.canRequestPackageInstalls()) {
            launchPackageInstaller(activity, pending)
        }
    }

    private fun launchPackageInstaller(activity: Activity, apkFile: File) {
        try {
            val uri = FileProvider.getUriForFile(activity, activity.packageName + ".share", apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(intent)
        } catch (_: Exception) {
            val cur = _state.value
            val rel = (cur as? UpdateState.ReadyToInstall)?.release
            _state.value = UpdateState.Error(UpdateError.INSTALL_FAILED, rel)
        }
    }

    /** Panic wipe: abort any transfer, reset preferences, and drop cached state. */
    fun reset(context: Context) {
        activeToken++
        activeCall?.cancel()
        activeCall = null
        waitingForRouteContext = null
        pendingInstallFile = null
        autoCheckedThisRun = false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        _state.value = UpdateState.Idle
    }

    private fun updateDir(context: Context) = File(context.cacheDir, "update")

    private fun saveVerifiedSha(context: Context, apkName: String, sha: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("verified_name", apkName)
            .putString("verified_sha", sha)
            .apply()
    }

    private fun cachedVerifiedApk(context: Context, release: ReleaseInfo): File? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString("verified_name", null) != release.apkName) return null
        val savedSha = prefs.getString("verified_sha", null) ?: return null
        val f = File(updateDir(context), release.apkName)
        if (!f.isFile) return null
        if (sha256Hex(f) != savedSha) return null
        if (verifyApkArchive(context, f) != null) return null
        return f
    }

    private fun installedVersionName(context: Context): String {
        val info = getPackageInfoCompat(context.packageManager, context.packageName, 0)
        return info?.versionName?.trim().orEmpty().ifEmpty { "0.0.0" }
    }

    private fun verifyApkArchive(context: Context, apkFile: File): UpdateError? {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val installed = getPackageInfoCompat(pm, context.packageName, flags) ?: return UpdateError.SIGNATURE_MISMATCH
        val archive = getArchiveInfoCompat(pm, apkFile.absolutePath, flags) ?: return UpdateError.SIGNATURE_MISMATCH

        if (archive.packageName != context.packageName) return UpdateError.SIGNATURE_MISMATCH
        if (PackageInfoCompat.getLongVersionCode(archive) < PackageInfoCompat.getLongVersionCode(installed)) {
            return UpdateError.DOWNGRADE_BLOCKED
        }

        val installedCerts = extractCertDigests(installed)
        val archiveCerts = extractCertDigests(archive)
        if (installedCerts.isEmpty() || archiveCerts != installedCerts) {
            return UpdateError.SIGNATURE_MISMATCH
        }
        return null
    }

    private fun getPackageInfoCompat(pm: PackageManager, pkg: String, flags: Int): PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, flags)
        }
    } catch (_: Exception) {
        null
    }

    private fun getArchiveInfoCompat(pm: PackageManager, path: String, flags: Int): PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(path, flags)
        }
    } catch (_: Exception) {
        null
    }

    private fun extractCertDigests(info: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val si = info.signingInfo ?: return emptySet()
            si.apkContentsSigners ?: si.signingCertificateHistory
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        } ?: return emptySet()
        return sigs.map { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).toHexLower()
        }.toSet()
    }

    private fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().toHexLower()
    }

    // ---- Pure JVM helpers (directly unit-tested) ----

    internal fun parseReleasesJson(json: String, currentVersion: String): ReleaseInfo? {
        val arr = JSONArray(json)
        var best: ReleaseInfo? = null
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optBoolean("draft", false)) continue
            val tag = obj.optString("tag_name", "").trim()
            if (tag.isEmpty()) continue
            val ver = normalizeVersion(tag)
            if (compareVersions(ver, normalizeVersion(currentVersion)) <= 0) continue

            val assets = obj.optJSONArray("assets") ?: continue
            var apkName: String? = null
            var apkUrl: String? = null
            var apkSize = 0L
            var sumsUrl: String? = null

            for (j in 0 until assets.length()) {
                val a = assets.optJSONObject(j) ?: continue
                val name = a.optString("name", "").trim()
                val url = a.optString("browser_download_url", "").trim()
                if (!isAllowedDownloadUrl(url)) continue
                when {
                    name.endsWith(".apk", ignoreCase = true) && (apkName == null || name.startsWith("openchat", ignoreCase = true)) -> {
                        apkName = name
                        apkUrl = url
                        apkSize = a.optLong("size", 0L)
                    }
                    name.equals("SHA256SUMS", ignoreCase = true) || name.endsWith(".sha256", ignoreCase = true) -> {
                        sumsUrl = url
                    }
                }
            }

            if (apkName == null || apkUrl == null || sumsUrl == null) continue
            val candidate = ReleaseInfo(
                tagName = tag,
                versionName = ver,
                title = obj.optString("name", "").trim().ifEmpty { tag },
                summary = cleanReleaseSummary(obj.optString("body", "")),
                apkName = apkName,
                apkUrl = apkUrl,
                apkSizeBytes = apkSize,
                sumsUrl = sumsUrl,
                prerelease = obj.optBoolean("prerelease", false),
            )
            if (best == null || compareVersions(candidate.versionName, best.versionName) > 0) {
                best = candidate
            }
        }
        return best
    }

    internal fun parseSha256Sums(sumsText: String, apkName: String): String? {
        val entries = ArrayList<Pair<String, String?>>()
        for (rawLine in sumsText.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split(Regex("\\s+"), limit = 2)
            val hash = parts[0].lowercase(Locale.ROOT)
            if (!HEX_64.matches(hash)) continue
            val filePart = parts.getOrNull(1)?.removePrefix("*")?.trim()?.substringAfterLast('/')
            entries += hash to filePart
        }
        entries.firstOrNull { it.second != null && it.second.equals(apkName, ignoreCase = true) }?.let { return it.first }
        if (entries.size == 1 && entries[0].second == null) return entries[0].first
        return null
    }

    internal fun isAllowedDownloadUrl(url: String): Boolean {
        if (!url.startsWith("https://", ignoreCase = true)) return false
        val host = try {
            java.net.URI(url).host?.lowercase(Locale.ROOT) ?: return false
        } catch (_: Exception) {
            return false
        }
        return host == "github.com" || host.endsWith(".github.com") || host.endsWith(".githubusercontent.com")
    }

    internal fun normalizeVersion(v: String): String = v.trim().removePrefix("v").removePrefix("V")

    /**
     * Semantic version ordering:
     * `0.8.0 > 0.7.10 > 0.7.3 > 0.7.3-rc1 > 0.7.3-beta2`.
     */
    internal fun compareVersions(aRaw: String, bRaw: String): Int {
        val a = normalizeVersion(aRaw)
        val b = normalizeVersion(bRaw)
        if (a == b) return 0
        val aCore = a.substringBefore('-')
        val bCore = b.substringBefore('-')
        val aParts = aCore.split('.').map { it.toIntOrNull() ?: 0 }
        val bParts = bCore.split('.').map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(aParts.size, bParts.size)
        for (i in 0 until maxLen) {
            val av = aParts.getOrElse(i) { 0 }
            val bv = bParts.getOrElse(i) { 0 }
            if (av != bv) return av.compareTo(bv)
        }
        val aSuffix = a.substringAfter('-', missingDelimiterValue = "")
        val bSuffix = b.substringAfter('-', missingDelimiterValue = "")
        if (aSuffix.isEmpty() && bSuffix.isEmpty()) return 0
        if (aSuffix.isEmpty()) return 1 // release > prerelease of same core
        if (bSuffix.isEmpty()) return -1
        return aSuffix.compareTo(bSuffix)
    }

    internal fun cleanReleaseSummary(markdown: String): String {
        val out = ArrayList<String>()
        var inCodeFence = false
        for (raw in markdown.lineSequence()) {
            val trimmed = raw.trim()
            if (trimmed.startsWith("```")) {
                inCodeFence = !inCodeFence
                continue
            }
            if (inCodeFence || trimmed.isEmpty()) continue
            if (trimmed.startsWith("## 校验") || trimmed.startsWith("## Checksum") || trimmed.startsWith("shasum ")) break
            if (trimmed.startsWith("#")) continue
            val plain = trimmed
                .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
                .replace("**", "")
                .replace("`", "")
                .trim()
            if (plain.isNotEmpty()) {
                out += plain
                if (out.size >= 5) break
            }
        }
        return out.joinToString("\n")
    }

    private val HEX_64 = Regex("^[0-9a-f]{64}$")
}

private fun java.io.InputStream.readNBytesCompat(maxBytes: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(4096)
    var total = 0
    while (total < maxBytes) {
        val n = read(buf, 0, minOf(buf.size, maxBytes - total))
        if (n < 0) break
        out.write(buf, 0, n)
        total += n
    }
    return out.toByteArray()
}

private fun ByteArray.toHexLower(): String {
    val chars = CharArray(size * 2)
    val hex = "0123456789abcdef"
    for (i in indices) {
        val v = this[i].toInt() and 0xff
        chars[i * 2] = hex[v ushr 4]
        chars[i * 2 + 1] = hex[v and 0x0f]
    }
    return String(chars)
}
