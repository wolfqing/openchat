package org.openchat.android.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * Media kept small enough to cross a Bluetooth mesh in a few seconds (§18):
 * photos re-encoded as JPEG, voice notes as AAC in an MP4 container.
 */
object Media {
    const val MAX_SEND_BYTES = 96 * 1024
    const val MAX_VOICE_MS = 30_000

    fun dir(context: Context) = File(context.cacheDir, "media").apply { mkdirs() }

    /** A photo scaled to at most [maxSide] pixels and compressed until it fits [MAX_SEND_BYTES]. */
    fun jpegFrom(context: Context, uri: Uri, maxSide: Int = 720): ByteArray? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("undecodable")
        val scale = maxSide.toFloat() / maxOf(decoded.width, decoded.height)
        val bmp = if (scale < 1f) Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true) else decoded
        var quality = 70
        var out: ByteArray
        do {
            out = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
            quality -= 10
        } while (out.size > MAX_SEND_BYTES && quality >= 20)
        out.takeIf { it.size <= MAX_SEND_BYTES }
    } catch (e: Exception) { null }

    /** Writes received or sent media to the cache, which the panic wipe deletes. */
    fun store(context: Context, bytes: ByteArray, ext: String): File =
        File(dir(context), "${UUID.randomUUID()}.$ext").apply { writeBytes(bytes) }

    /** The same picture reduced to about [blocks] cells across, for the covered view. */
    fun pixelate(bmp: Bitmap, blocks: Int = 12): Bitmap {
        val w = blocks.coerceAtLeast(1)
        val h = maxOf(1, w * bmp.height / maxOf(1, bmp.width))
        return Bitmap.createScaledBitmap(bmp, w, h, true)
    }

    /** Decodes an image for display, sampled down to about [maxSide] pixels. */
    fun thumbnail(path: String, maxSide: Int = 720): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (e: Exception) { null }
}

/** Records one voice note at a time. */
class VoiceRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    val recording get() = recorder != null

    fun start(): Boolean = try {
        val f = File(Media.dir(context), "rec-${UUID.randomUUID()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioChannels(1)
        r.setAudioSamplingRate(22_050)
        r.setAudioEncodingBitRate(20_000)
        r.setMaxDuration(Media.MAX_VOICE_MS)
        r.setOutputFile(f.absolutePath)
        r.prepare(); r.start()
        recorder = r; file = f; startedAt = System.currentTimeMillis()
        true
    } catch (e: Exception) { cancel(); false }

    /** Stops and returns the note, or null if it was too short or failed. */
    fun stop(): File? {
        val r = recorder ?: return null
        val f = file
        val long = System.currentTimeMillis() - startedAt >= 700
        recorder = null; file = null
        return try { r.stop(); r.release(); f?.takeIf { long && it.length() > 0 } } catch (e: Exception) { r.release(); f?.delete(); null }
    }

    fun cancel() { try { recorder?.release() } catch (e: Exception) { }; recorder = null; file?.delete(); file = null }
}
