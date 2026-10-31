package org.openchat.android.app

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Handler
import org.openchat.android.core.Adts
import org.openchat.android.core.BurstAssembly
import org.openchat.android.core.VoiceBurst
import org.openchat.android.core.VoicePacketizer
import org.openchat.android.core.toHex
import org.openchat.android.crypto.Crypto
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** One switch for live voice in both directions, on by default as in bitchat (§29). */
object LiveVoicePrefs {
    private fun prefs(c: Context) = c.getSharedPreferences("voice", Context.MODE_PRIVATE)
    fun enabled(c: Context) = prefs(c).getBoolean("live", true)
    fun setEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean("live", on).apply()
    fun wipe(c: Context) = prefs(c).edit().clear().apply()
}

private const val SAMPLE_RATE = 16_000
private const val FRAME_SAMPLES = 1024 // one AAC-LC frame, 64 ms
private const val MIME = MediaFormat.MIMETYPE_AUDIO_AAC

/**
 * Records a voice note while streaming it live (§29): microphone → AAC-LC 16 kHz mono at
 * 16 kbps; each encoded frame goes both into `voice_<burst>.m4a`, the note sent at the end,
 * and into burst packets handed to [send] as they fill. Packets are sent only when [live].
 */
class LiveCapture(context: Context, private val live: Boolean, private val send: (ByteArray) -> Unit) {
    val burstId: ByteArray = Crypto.randomBytes(VoiceBurst.ID_LENGTH)
    val file = File(Media.dir(context), "voice_${burstId.toHex()}.m4a")
    private val packetizer = VoicePacketizer(burstId)
    @Volatile private var running = false
    private var thread: Thread? = null
    private var frames = 0
    private var startedAt = 0L

    @SuppressLint("MissingPermission") // the caller asked for RECORD_AUDIO
    fun start(): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        val record = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, FRAME_SAMPLES * 8))
        } catch (e: Exception) { return false }
        if (record.state != AudioRecord.STATE_INITIALIZED) { record.release(); return false }
        val encoder = try {
            MediaCodec.createEncoderByType(MIME).apply {
                configure(MediaFormat.createAudioFormat(MIME, SAMPLE_RATE, 1).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, 16_000)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME_SAMPLES * 2)
                }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
        } catch (e: Exception) { record.release(); return false }
        val muxer = try { MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4) } catch (e: Exception) { encoder.release(); record.release(); return false }
        try { record.startRecording() } catch (e: Exception) { encoder.release(); muxer.release(); record.release(); return false }
        running = true
        startedAt = System.currentTimeMillis()
        if (live) send(VoiceBurst(burstId, 0, VoiceBurst.Kind.Start(VoiceBurst.CODEC_AAC_LC_16K_MONO)).encode())
        thread = Thread({ loop(record, encoder, muxer) }, "live-voice").apply { start() }
        return true
    }

    private fun loop(record: AudioRecord, encoder: MediaCodec, muxer: MediaMuxer) {
        val pcm = ByteArray(FRAME_SAMPLES * 2)
        val info = MediaCodec.BufferInfo()
        var track = -1
        var samples = 0L
        var eosQueued = false
        try {
            while (true) {
                if (!eosQueued) {
                    val i = encoder.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val stop = !running || System.currentTimeMillis() - startedAt >= Media.MAX_VOICE_MS
                        if (stop) {
                            encoder.queueInputBuffer(i, 0, 0, samples * 1_000_000 / SAMPLE_RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosQueued = true
                        } else {
                            val n = record.read(pcm, 0, pcm.size).coerceAtLeast(0)
                            encoder.getInputBuffer(i)!!.apply { clear(); put(pcm, 0, n) }
                            encoder.queueInputBuffer(i, 0, n, samples * 1_000_000 / SAMPLE_RATE, 0)
                            samples += n / 2
                        }
                    }
                }
                val o = encoder.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(encoder.outputFormat); muxer.start() }
                    o >= 0 -> {
                        val buf = encoder.getOutputBuffer(o)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && track >= 0) {
                            buf.position(info.offset); buf.limit(info.offset + info.size)
                            val frame = ByteArray(info.size).also { buf.get(it) }
                            buf.position(info.offset)
                            muxer.writeSampleData(track, buf, info)
                            frames++
                            if (live) packetizer.add(frame).forEach(send)
                        }
                        encoder.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } catch (e: Exception) {
            frames = 0
        } finally {
            try { record.stop() } catch (e: Exception) { }
            record.release()
            try { encoder.stop() } catch (e: Exception) { }
            encoder.release()
            try { if (track >= 0) muxer.stop() } catch (e: Exception) { frames = 0 }
            muxer.release()
        }
    }

    private fun join() { running = false; thread?.join(2_000); thread = null }

    /** Stops; returns the finished note, or null (and a cancel to listeners) when too short or broken. */
    fun finish(): File? {
        join()
        val durationMs = frames.toLong() * FRAME_SAMPLES * 1000 / SAMPLE_RATE
        if (durationMs < MIN_MS || !file.exists() || file.length() == 0L) {
            if (live) send(packetizer.control(VoiceBurst.Kind.Canceled))
            file.delete()
            return null
        }
        if (live) {
            packetizer.flush().forEach(send)
            send(packetizer.control(VoiceBurst.Kind.End(packetizer.dataPackets, durationMs)))
        }
        return file
    }

    fun cancel() {
        val wasRunning = running
        join()
        if (live && wasRunning) send(packetizer.control(VoiceBurst.Kind.Canceled))
        file.delete()
    }

    private companion object { const val MIN_MS = 700L }
}

/** Decodes a live burst's AAC frames and plays them, after a short jitter buffer. */
private class LivePlayer {
    private val queue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var finishing = false
    @Volatile private var stopped = false

    init { Thread(::run, "live-voice-play").start() }

    fun enqueue(frames: List<ByteArray>) { if (!stopped) frames.forEach { queue.put(it) } }
    fun finishAfterDrain() { finishing = true }
    fun stop() { stopped = true; queue.clear() }

    private fun run() {
        val decoder = try {
            MediaCodec.createDecoderByType(MIME).apply {
                configure(MediaFormat.createAudioFormat(MIME, SAMPLE_RATE, 1).apply {
                    setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(Adts.CONFIG))
                    setInteger(MediaFormat.KEY_IS_ADTS, 0)
                }, null, null, 0)
                start()
            }
        } catch (e: Exception) { return }
        val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(minBuf, FRAME_SAMPLES * 8))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) { decoder.release(); return }
        val info = MediaCodec.BufferInfo()
        try {
            // Jitter buffer: about 350 ms of audio, or half a second of waiting, whichever comes first.
            val waitUntil = System.currentTimeMillis() + 500
            while (!stopped && queue.size < 6 && System.currentTimeMillis() < waitUntil && !finishing) Thread.sleep(20)
            track.play()
            while (!stopped) {
                val frame = queue.poll(100, TimeUnit.MILLISECONDS)
                if (frame == null) { if (finishing) break else continue }
                val i = decoder.dequeueInputBuffer(20_000)
                if (i >= 0) { decoder.getInputBuffer(i)!!.apply { clear(); put(frame) }; decoder.queueInputBuffer(i, 0, frame.size, 0, 0) }
                while (true) {
                    val o = decoder.dequeueOutputBuffer(info, 0)
                    if (o < 0) break
                    val pcm = ByteArray(info.size)
                    decoder.getOutputBuffer(o)!!.apply { position(info.offset); get(pcm) }
                    decoder.releaseOutputBuffer(o, false)
                    if (!stopped) track.write(pcm, 0, pcm.size)
                }
            }
        } catch (e: Exception) {
        } finally {
            try { track.stop() } catch (e: Exception) { }
            track.release()
            try { decoder.stop() } catch (e: Exception) { }
            decoder.release()
        }
    }
}

/**
 * Inbound live bursts (§29), bitchat's way: each becomes a voice bubble at once, written
 * as an ADTS file so even a partial burst can be replayed, and played live when that chat
 * is open. The sender's finished `voice_<burst>.m4a` later replaces the partial file in the
 * same bubble. Runs on the mesh thread.
 */
class LiveVoice(private val context: Context, private val handler: Handler) {
    private class Burst(
        val key: String, val senderHex: String, val isPrivate: Boolean, val conversation: String, val messageId: String,
        val timestamp: Long, val assembly: BurstAssembly, val file: File, var out: FileOutputStream?, var player: LivePlayer?,
    )

    private val bursts = LinkedHashMap<String, Burst>()
    private val finished = LinkedHashMap<String, Burst>() // waiting for the finished note, 10 minutes
    private var ticking = false

    private fun key(sender: String, isPrivate: Boolean, burstHex: String) = "$sender/${if (isPrivate) "dm" else "mesh"}/$burstHex"

    fun onFrame(senderHex: String, nickname: String, content: ByteArray, isPrivate: Boolean) {
        if (!LiveVoicePrefs.enabled(context)) return
        val p = VoiceBurst.decode(content) ?: return
        // A burst ID only names a burst within its authenticated sender and chat.
        val k = key(senderHex, isPrivate, p.burstHex)
        val b = bursts[k] ?: run {
            if (p.kind is VoiceBurst.Kind.End || p.kind == VoiceBurst.Kind.Canceled || k in finished) return
            if (bursts.size >= BurstAssembly.MAX_CONCURRENT) return
            open(k, senderHex, nickname, isPrivate, p.burstHex)
        }
        deliver(b, b.assembly.accept(p, content.size))
        settle(b)
        armTick()
    }

    private fun open(k: String, senderHex: String, nickname: String, isPrivate: Boolean, burstHex: String): Burst {
        val conv = if (isPrivate) Conversation.Private(senderHex).key else Conversation.Mesh.key
        val file = File(Media.dir(context), "voice_live_${burstHex}_${senderHex}_${if (isPrivate) "dm" else "mesh"}.aac")
        val now = System.currentTimeMillis()
        val b = Burst(k, senderHex, isPrivate, conv, "live-$burstHex-$senderHex", now, BurstAssembly(System::currentTimeMillis), file, FileOutputStream(file), null)
        bursts[k] = b
        ChatRepository.rememberName(senderHex, nickname)
        ChatRepository.add(ChatMessage(b.messageId, conv, nickname, senderHex, "", now, mine = false, attachment = file.absolutePath, audio = true))
        if (ChatRepository.foreground && ChatRepository.openConversation == conv) b.player = LivePlayer()
        return b
    }

    private fun deliver(b: Burst, frames: List<ByteArray>) {
        if (frames.isEmpty()) return
        try { b.out?.let { o -> frames.forEach { o.write(Adts.frame(it)) } } } catch (e: Exception) { b.out = null }
        b.player?.enqueue(frames)
    }

    /** Closes a burst that just finished or was canceled. */
    private fun settle(b: Burst) {
        if (b.assembly.state == BurstAssembly.State.OPEN) return
        bursts.remove(b.key)
        try { b.out?.close() } catch (e: Exception) { }
        b.out = null
        if (b.assembly.state == BurstAssembly.State.CANCELED || b.assembly.delivered == 0) {
            b.player?.stop()
            ChatRepository.remove(b.conversation, b.messageId)
            b.file.delete()
            return
        }
        b.player?.finishAfterDrain()
        finished[b.key] = b
        while (finished.size > 32) finished.remove(finished.keys.first())
    }

    private fun armTick() {
        if (ticking || bursts.isEmpty()) return
        ticking = true
        handler.postDelayed({
            ticking = false
            bursts.values.toList().forEach { b -> deliver(b, b.assembly.tick()); settle(b) }
            armTick()
        }, TICK_MS)
    }

    /**
     * A received voice note: when it is the finished file of a burst we assembled (same
     * sender, same chat, `voice_<burst>` name), it takes over that bubble; true means handled.
     */
    fun absorb(senderHex: String, isPrivate: Boolean, fileName: String?, stored: File): Boolean {
        val hex = fileName?.removePrefix("voice_")?.takeIf { fileName.startsWith("voice_") }?.take(16)?.takeIf { h -> h.length == 16 && h.all { it.isDigit() || it in 'a'..'f' } } ?: return false
        val k = key(senderHex, isPrivate, hex)
        bursts[k]?.let { b -> deliver(b, b.assembly.finish()); settle(b) }
        val b = finished.remove(k) ?: return false
        val old = ChatRepository.messages.value[b.conversation].orEmpty().firstOrNull { it.id == b.messageId } ?: return false
        ChatRepository.remove(b.conversation, b.messageId)
        ChatRepository.add(old.copy(id = "file-${stored.nameWithoutExtension}", attachment = stored.absolutePath))
        b.file.delete()
        return true
    }

    /** Panic wipe: stop every stream and forget it. */
    fun wipe() {
        (bursts.values + finished.values).forEach { it.player?.stop(); try { it.out?.close() } catch (e: Exception) { }; it.file.delete() }
        bursts.clear(); finished.clear()
        LiveVoicePrefs.wipe(context)
    }

    private companion object { const val TICK_MS = 250L }
}
