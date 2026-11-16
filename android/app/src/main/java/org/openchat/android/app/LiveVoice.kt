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
import android.media.MediaRecorder
import android.os.Handler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.openchat.android.core.Adts
import org.openchat.android.core.VoiceBurst
import org.openchat.android.core.VoicePacketizer
import org.openchat.android.crypto.Crypto
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/** One switch for live walkie-talkie voice in both directions, on by default as in bitchat (§29). */
object LiveVoicePrefs {
    private val _enabledFlow = MutableStateFlow(true)
    val enabledFlow: StateFlow<Boolean> = _enabledFlow.asStateFlow()

    private fun prefs(c: Context) = c.getSharedPreferences("voice", Context.MODE_PRIVATE)
    fun enabled(c: Context): Boolean = prefs(c).getBoolean("live", true)
    fun sync(c: Context): Boolean = enabled(c).also { _enabledFlow.value = it }
    fun setEnabled(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("live", on).apply()
        _enabledFlow.value = on
        ChatRepository.onLiveVoiceEnabledChanged?.invoke(on)
    }
    fun wipe(c: Context) {
        prefs(c).edit().clear().apply()
        _enabledFlow.value = true
    }
}

/** Normalized 0..1 voice level from 16-bit little-endian mono PCM samples, driving PTT equalizer bars. */
internal fun pcmLevel(pcm: ByteArray, bytesRead: Int): Float {
    val samples = bytesRead / 2
    if (samples <= 0) return 0f
    var sumSq = 0.0
    var i = 0
    while (i + 1 < bytesRead) {
        val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort()
        val v = sample / 32768.0
        sumSq += v * v
        i += 2
    }
    val rms = sqrt(sumSq / samples).toFloat()
    return (rms * 4.5f).coerceIn(0f, 1f)
}

private const val SAMPLE_RATE = 16_000
private const val FRAME_SAMPLES = 1024 // one AAC-LC frame, 64 ms
private const val MIME = MediaFormat.MIMETYPE_AUDIO_AAC

/**
 * Pure real-time Walkie-Talkie capture (§29): microphone → AAC-LC 16 kHz mono at 16 kbps →
 * [VoiceBurst] packets handed straight to [send] without writing a voice-note bubble to disk.
 */
class LivePttCapture(
    private val context: Context,
    private val send: (ByteArray) -> Unit,
    private val onMaxDurationReached: () -> Unit = {},
) {
    val burstId: ByteArray = Crypto.randomBytes(VoiceBurst.ID_LENGTH)
    private val packetizer = VoicePacketizer(burstId)
    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile private var frames = 0

    @SuppressLint("MissingPermission") // caller verified RECORD_AUDIO
    fun start(): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, FRAME_SAMPLES * 8),
            )
        } catch (e: Exception) {
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        val encoder = try {
            MediaCodec.createEncoderByType(MIME).apply {
                configure(
                    MediaFormat.createAudioFormat(MIME, SAMPLE_RATE, 1).apply {
                        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                        setInteger(MediaFormat.KEY_BIT_RATE, 16_000)
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME_SAMPLES * 2)
                    },
                    null,
                    null,
                    MediaCodec.CONFIGURE_FLAG_ENCODE,
                )
                start()
            }
        } catch (e: Exception) {
            record.release()
            return false
        }
        running = true
        send(VoiceBurst(burstId, 0, VoiceBurst.Kind.Start(VoiceBurst.CODEC_AAC_LC_16K_MONO)).encode())
        thread = Thread({ loop(record, encoder) }, "live-ptt").apply { start() }
        return true
    }

    private fun loop(record: AudioRecord, encoder: MediaCodec) {
        val pcm = ByteArray(FRAME_SAMPLES * 2)
        val info = MediaCodec.BufferInfo()
        var samples = 0L
        var eosQueued = false
        var hitMaxLimit = false
        try {
            // Play the short PTT rising chirp before opening the microphone so it never bleeds into TX audio.
            if (running) PttTones.playStartBlocking(context)
            if (!running) return
            record.startRecording()
            val startedAt = System.currentTimeMillis()
            while (true) {
                if (!eosQueued) {
                    val i = encoder.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val timedOut = System.currentTimeMillis() - startedAt >= PttFloorCoordinator.MAX_HOLD_MS
                        if (!running || timedOut) {
                            if (timedOut && running) hitMaxLimit = true
                            encoder.queueInputBuffer(i, 0, 0, samples * 1_000_000 / SAMPLE_RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosQueued = true
                        } else {
                            val n = record.read(pcm, 0, pcm.size).coerceAtLeast(0)
                            if (n > 0) ChatRepository.setLiveMicLevel(pcmLevel(pcm, n))
                            encoder.getInputBuffer(i)!!.apply { clear(); put(pcm, 0, n) }
                            encoder.queueInputBuffer(i, 0, n, samples * 1_000_000 / SAMPLE_RATE, 0)
                            samples += n / 2
                        }
                    }
                }
                val o = encoder.dequeueOutputBuffer(info, 10_000)
                if (o >= 0) {
                    val buf = encoder.getOutputBuffer(o)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val frame = ByteArray(info.size).also { buf.get(it) }
                        frames++
                        packetizer.add(frame).forEach(send)
                    }
                    encoder.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } catch (e: Exception) {
            frames = 0
        } finally {
            ChatRepository.setLiveMicLevel(0f)
            try { if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop() } catch (e: Exception) { }
            record.release()
            try { encoder.stop() } catch (e: Exception) { }
            encoder.release()
            if (hitMaxLimit) onMaxDurationReached()
        }
    }

    private fun join() {
        running = false
        thread?.join(1_500)
        thread = null
    }

    /** Stops and flushes `END` when [validHold] and audible frames were captured; otherwise sends `CANCELED`. */
    fun finish(validHold: Boolean): Boolean {
        join()
        val durationMs = frames.toLong() * FRAME_SAMPLES * 1000 / SAMPLE_RATE
        if (!validHold || frames == 0 || durationMs < PttFloorCoordinator.MIN_HOLD_MS) {
            send(packetizer.control(VoiceBurst.Kind.Canceled))
            return false
        }
        packetizer.flush().forEach(send)
        send(packetizer.control(VoiceBurst.Kind.End(packetizer.dataPackets, durationMs)))
        return true
    }

    fun cancel() {
        val wasRunning = running
        join()
        if (wasRunning) send(packetizer.control(VoiceBurst.Kind.Canceled))
    }
}

/** Decodes a live burst's AAC frames and plays them after a brief jitter buffer, reporting RX voice level. */
private class LivePlayer(private val onDrainedCleanly: (() -> Unit)? = null) {
    private val queue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var finishing = false
    @Volatile private var stopped = false

    init { Thread(::run, "live-ptt-play").start() }

    fun enqueue(frames: List<ByteArray>) { if (!stopped) frames.forEach { queue.put(it) } }
    fun finishAfterDrain() { finishing = true }
    fun stop() { stopped = true; queue.clear() }

    private fun run() {
        val decoder = try {
            MediaCodec.createDecoderByType(MIME).apply {
                configure(
                    MediaFormat.createAudioFormat(MIME, SAMPLE_RATE, 1).apply {
                        setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(Adts.CONFIG))
                        setInteger(MediaFormat.KEY_IS_ADTS, 0)
                    },
                    null,
                    null,
                    0,
                )
                start()
            }
        } catch (e: Exception) {
            return
        }
        val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuf, FRAME_SAMPLES * 8))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            decoder.release()
            return
        }
        val info = MediaCodec.BufferInfo()
        var wroteAudio = false
        try {
            // Snappy walkie-talkie jitter buffer: ~250 ms (4 frames) or 350 ms max wait.
            val waitUntil = System.currentTimeMillis() + 350
            while (!stopped && queue.size < 4 && System.currentTimeMillis() < waitUntil && !finishing) {
                Thread.sleep(20)
            }
            track.play()
            while (!stopped) {
                val frame = queue.poll(80, TimeUnit.MILLISECONDS)
                if (frame == null) {
                    if (finishing) break else {
                        ChatRepository.setLiveRxLevel(0f)
                        continue
                    }
                }
                val i = decoder.dequeueInputBuffer(20_000)
                if (i >= 0) {
                    decoder.getInputBuffer(i)!!.apply { clear(); put(frame) }
                    decoder.queueInputBuffer(i, 0, frame.size, 0, 0)
                }
                while (true) {
                    val o = decoder.dequeueOutputBuffer(info, 0)
                    if (o < 0) break
                    val pcm = ByteArray(info.size)
                    decoder.getOutputBuffer(o)!!.apply { position(info.offset); get(pcm) }
                    decoder.releaseOutputBuffer(o, false)
                    if (!stopped && pcm.isNotEmpty()) {
                        wroteAudio = true
                        ChatRepository.setLiveRxLevel(pcmLevel(pcm, pcm.size))
                        track.write(pcm, 0, pcm.size)
                    }
                }
            }
        } catch (e: Exception) {
        } finally {
            ChatRepository.setLiveRxLevel(0f)
            try { track.stop() } catch (e: Exception) { }
            track.release()
            try { decoder.stop() } catch (e: Exception) { }
            decoder.release()
            if (!stopped && finishing && wroteAudio) {
                onDrainedCleanly?.invoke()
            }
        }
    }
}

/**
 * Inbound & floor-managed Walkie-Talkie engine (§29):
 * plays live bursts immediately without cluttering the chat timeline with voice bubbles,
 * enforces half-duplex single-speaker occupancy per channel via [PttFloorCoordinator],
 * and absorbs duplicate trailing `.m4a` notes from stock bitchat peers when already heard live.
 */
class LiveVoice(
    private val context: Context,
    private val handler: Handler,
    private val onPreemptLocalTx: (conversation: String) -> Unit = {},
) {
    val coordinator = PttFloorCoordinator()
    private val players = HashMap<String, LivePlayer>()
    private var ticking = false

    init {
        ChatRepository.onLiveVoiceEnabledChanged = { on ->
            handler.post {
                if (!on) {
                    players.values.forEach { it.stop() }
                    players.clear()
                    ChatRepository.setLiveRxLevel(0f)
                }
            }
        }
    }

    fun syncUiStates() {
        val states = coordinator.snapshotStates()
        ChatRepository.setPttStates(states)
        val speakers = HashMap<String, LiveSpeaker>()
        for ((conv, st) in states) {
            if (st is PttFloorState.Receiving) {
                speakers[conv] = LiveSpeaker(conv, st.senderKey, st.senderName, st.startedAt, st.hops)
            }
        }
        ChatRepository.replaceLiveSpeakers(speakers)
    }

    fun onFrame(myPeerHex: String, senderHex: String, nickname: String, content: ByteArray, isPrivate: Boolean, hops: Int?) {
        val p = VoiceBurst.decode(content) ?: return
        val conv = if (isPrivate) Conversation.Private(senderHex).key else Conversation.Mesh.key
        val playLive = LiveVoicePrefs.enabled(context) && ChatRepository.foreground && ChatRepository.openConversation == conv
        val outcome = coordinator.onRemotePacket(conv, myPeerHex, senderHex, nickname, hops, p, content.size, playLive)
        if (outcome !is PttRemoteOutcome.Active) return

        if (nickname.isNotEmpty()) ChatRepository.rememberName(senderHex, nickname)
        applyOutcome(outcome, playLive)
        syncUiStates()
        armTick()
    }

    private fun applyOutcome(outcome: PttRemoteOutcome.Active, playLive: Boolean) {
        val conv = outcome.conversation
        if (outcome.preemptedLocalTx) {
            onPreemptLocalTx(conv)
            PttTones.playBusy(context)
        }
        if (outcome.startedNew) {
            players.remove(conv)?.stop()
            if (playLive && !outcome.canceled) {
                players[conv] = LivePlayer(onDrainedCleanly = { PttTones.playEnd(context) })
            }
        }
        if (outcome.frames.isNotEmpty()) {
            players[conv]?.enqueue(outcome.frames)
        }
        if (outcome.canceled) {
            players.remove(conv)?.stop()
        } else if (outcome.finishedCleanly) {
            players.remove(conv)?.finishAfterDrain()
        }
    }

    private fun armTick() {
        if (ticking || !coordinator.hasActiveRx()) return
        ticking = true
        handler.postDelayed({
            ticking = false
            val updates = coordinator.tick()
            for (u in updates) {
                val playLive = LiveVoicePrefs.enabled(context) && ChatRepository.foreground && ChatRepository.openConversation == u.conversation
                applyOutcome(u, playLive)
            }
            if (updates.isNotEmpty()) syncUiStates()
            armTick()
        }, TICK_MS)
    }

    /**
     * When a stock bitchat sender follows its live burst with a `voice_<burst>.m4a` file packet,
     * drop the duplicate file if we already played that burst live out loud; otherwise let `onFile`
     * show it as an ordinary missed voice note.
     */
    fun absorb(senderHex: String, isPrivate: Boolean, fileName: String?, stored: File): Boolean {
        val conv = if (isPrivate) Conversation.Private(senderHex).key else Conversation.Mesh.key
        if (!coordinator.shouldAbsorbVoiceNote(conv, senderHex, fileName)) return false
        stored.delete()
        return true
    }

    /** Panic wipe: stop every stream and forget all floor state. */
    fun wipe() {
        players.values.forEach { it.stop() }
        players.clear()
        coordinator.wipe()
        ChatRepository.setLiveRxLevel(0f)
        ChatRepository.setPttStates(emptyMap())
        ChatRepository.clearLiveSpeakers()
        LiveVoicePrefs.wipe(context)
    }

    private companion object { const val TICK_MS = 250L }
}
