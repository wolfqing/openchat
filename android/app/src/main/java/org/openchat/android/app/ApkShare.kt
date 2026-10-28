package org.openchat.android.app

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Offline distribution: hands this app's own APK to the system share sheet,
 * so it can travel by Bluetooth, Quick Share / Nearby Share or any other
 * installed app, with no app store and no internet.
 */
object ApkShare {
    fun intent(context: Context): Intent {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val out = File(dir, "openchat.apk")
        val source = File(context.applicationInfo.sourceDir)
        if (!out.exists() || out.length() != source.length()) source.copyTo(out, overwrite = true)
        val uri = FileProvider.getUriForFile(context, context.packageName + ".share", out)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("openchat.apk", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, null)
    }
}
