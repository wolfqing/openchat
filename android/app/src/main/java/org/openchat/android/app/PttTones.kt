package org.openchat.android.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Zero-asset synthesized walkie-talkie cues (16 kHz mono PCM with 5 ms cosine fade-in/out):
 * - [playStartBlocking]: two-tone rising chirp (`880 Hz -> 1175 Hz`), run on the capture thread
 *   right before `AudioRecord.startRecording()` so the chirp never bleeds into transmitted audio.
 * - [playEnd]: crisp single-tone Roger beep (`1046 Hz`) on clean TX/RX release.
 * - [playBusy]: low double-beep (`330 Hz + 330 Hz`) when pressing an occupied channel or yielding on collision.
 */
object PttTones {
    private const val SAMPLE_RATE = 16_000
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ptt-tone").apply { isDaemon = true }
    }

    private val startPcm: ShortArray by lazy {
        concat(tone(880.0, 30, amplitude = 0.26), silence(8), tone(1175.0, 35, amplitude = 0.28))
    }

    private val endPcm: ShortArray by lazy {
        tone(1046.5, 55, amplitude = 0.24)
    }

    private val busyPcm: ShortArray by lazy {
        concat(tone(330.0, 45, amplitude = 0.28), silence(30), tone(330.0, 45, amplitude = 0.28))
    }

    fun playStartBlocking(context: Context) {
        if (!LiveVoicePrefs.enabled(context)) return
        playPcmBlocking(startPcm)
    }

    fun playEnd(context: Context) {
        if (!LiveVoicePrefs.enabled(context)) return
        executor.execute { playPcmBlocking(endPcm) }
    }

    fun playBusy(context: Context) {
        if (!LiveVoicePrefs.enabled(context)) return
        executor.execute { playPcmBlocking(busyPcm) }
    }

    /** Synthesizes a single sine tone with 5 ms raised-cosine fade-in/out to prevent clicks. */
    internal fun tone(freqHz: Double, durationMs: Int, amplitude: Double): ShortArray {
        val count = (SAMPLE_RATE * durationMs / 1000).coerceAtLeast(1)
        val fadeSamples = (SAMPLE_RATE * 5 / 1000).coerceAtMost(count / 2).coerceAtLeast(1)
        val out = ShortArray(count)
        for (i in 0 until count) {
            val env = when {
                i < fadeSamples -> 0.5 * (1.0 - cos(PI * i / fadeSamples))
                i >= count - fadeSamples -> 0.5 * (1.0 - cos(PI * (count - 1 - i) / fadeSamples))
                else -> 1.0
            }
            val sample = sin(2.0 * PI * freqHz * i / SAMPLE_RATE) * amplitude * env
            out[i] = (sample * 32767.0).toInt().coerceIn(-32767, 32767).toShort()
        }
        return out
    }

    private fun silence(durationMs: Int): ShortArray = ShortArray((SAMPLE_RATE * durationMs / 1000).coerceAtLeast(1))

    private fun concat(vararg parts: ShortArray): ShortArray {
        val total = parts.sumOf { it.size }
        val out = ShortArray(total)
        var pos = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, pos, p.size)
            pos += p.size
        }
        return out
    }

    private fun playPcmBlocking(samples: ShortArray) {
        val byteLen = samples.size * 2
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(byteLen)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
        } catch (e: Exception) {
            return
        }
        try {
            track.write(samples, 0, samples.size)
            track.play()
            val durationMs = (samples.size * 1000L / SAMPLE_RATE) + 25L
            Thread.sleep(durationMs)
        } catch (e: Exception) {
        } finally {
            try { track.stop() } catch (e: Exception) { }
            track.release()
        }
    }
}
