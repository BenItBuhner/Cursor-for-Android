package com.cursorforandroid.data.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.ln
import kotlin.math.sqrt

/** The call's microphone: 16-bit mono PCM at [RealtimeWire.SAMPLE_RATE], handed over in short chunks as it comes. */
interface CallMicrophone {
    /** Opens the microphone; throws when the device will not hand it over. [onChunk] is called off the main thread. */
    fun start(onChunk: (ByteArray) -> Unit)

    /** The loudness of the latest chunk, 0 to 1 on a meter's log scale. */
    fun level(): Float

    fun stop()
}

/** The call's speaker: the voice agent's PCM, played in the order it comes. */
interface CallSpeaker {
    /** Queues [pcm] of the reply item [itemId] (content [contentIndex]). */
    fun play(pcm: ByteArray, itemId: String, contentIndex: Int)

    /**
     * Stops at once and drops what is queued (the caller cut in); returns what was being said and how much of it
     * had been heard, for `conversation.item.truncate`, or null when nothing was playing.
     */
    fun interrupt(): Heard?

    /** Whether anything queued is still to be heard. */
    fun busy(): Boolean

    /** The loudness of what is being played, 0 to 1. */
    fun level(): Float

    fun release()

    data class Heard(val itemId: String, val contentIndex: Int, val audioEndMs: Long)
}

/** 0..1 from 16-bit samples, on the log scale the composer's meter reads (about -60 dB to 0). */
internal fun pcmLevel(pcm: ByteArray, length: Int = pcm.size): Float {
    val samples = length / 2
    if (samples == 0) return 0f
    var sum = 0.0
    for (i in 0 until samples) {
        val s = ((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xFF)).toShort().toDouble() / 32768.0
        sum += s * s
    }
    val rms = sqrt(sum / samples)
    if (rms <= 1e-4) return 0f
    val db = 20 * ln(rms) / ln(10.0)
    return ((db + 60) / 60).toFloat().coerceIn(0f, 1f)
}

/**
 * [CallMicrophone] on [AudioRecord] with the voice-communication source, so the platform's echo canceller keeps the
 * voice agent's own words, played by [AndroidCallSpeaker], out of what it hears.
 */
class AndroidCallMicrophone : CallMicrophone {
    @Volatile private var running = false
    @Volatile private var lastLevel = 0f
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var echo: AcousticEchoCanceler? = null
    private var noise: NoiseSuppressor? = null

    @SuppressLint("MissingPermission") // The call asks for RECORD_AUDIO before it starts.
    override fun start(onChunk: (ByteArray) -> Unit) {
        val rate = RealtimeWire.SAMPLE_RATE
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(min > 0) { "This device cannot record at $rate Hz." }
        val next = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, CHUNK_BYTES * 4))
        if (next.state != AudioRecord.STATE_INITIALIZED) {
            next.release()
            error("The microphone could not be opened.")
        }
        if (AcousticEchoCanceler.isAvailable()) echo = AcousticEchoCanceler.create(next.audioSessionId)?.apply { enabled = true }
        if (NoiseSuppressor.isAvailable()) noise = NoiseSuppressor.create(next.audioSessionId)?.apply { enabled = true }
        next.startRecording()
        if (next.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            next.release()
            error("The microphone is in use by another app.")
        }
        record = next
        running = true
        thread = Thread({
            val buffer = ByteArray(CHUNK_BYTES)
            while (running) {
                val n = next.read(buffer, 0, buffer.size)
                if (n <= 0) continue
                lastLevel = pcmLevel(buffer, n)
                onChunk(buffer.copyOf(n))
            }
        }, "call-microphone").apply { isDaemon = true; start() }
    }

    override fun level(): Float = lastLevel

    override fun stop() {
        running = false
        thread?.join(500)
        thread = null
        record?.let { runCatching { it.stop() }; it.release() }
        record = null
        echo?.release(); echo = null
        noise?.release(); noise = null
        lastLevel = 0f
    }

    companion object {
        /** 40 ms of audio a chunk, near the desktop's capture worklet's frames. */
        const val CHUNK_BYTES = RealtimeWire.SAMPLE_RATE / 25 * 2
    }
}

/**
 * [CallSpeaker] on a streaming [AudioTrack] for voice communication, routed to the loudspeaker unless a headset or
 * Bluetooth device is on: a call held at arm's length, as Cursor's is at the desk. Written from its own thread so
 * the socket's thread never waits on the audio.
 */
class AndroidCallSpeaker(context: Context) : CallSpeaker {
    private val audio = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val previousMode = audio.mode
    private val track: AudioTrack
    private val queue = LinkedBlockingQueue<Chunk>()
    @Volatile private var running = true
    @Volatile private var lastLevel = 0f
    /** Frames handed to the track so far, and where the current item started in them. */
    @Volatile private var framesWritten = 0L
    @Volatile private var current: Item? = null
    private val writer: Thread

    private class Chunk(val pcm: ByteArray, val itemId: String, val contentIndex: Int, val generation: Int)
    private class Item(val itemId: String, val contentIndex: Int, val startFrame: Long)
    @Volatile private var generation = 0

    init {
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        routeToLoudspeaker()
        val rate = RealtimeWire.SAMPLE_RATE
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(min, rate / 5 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
        writer = Thread({
            while (running) {
                val chunk = try { queue.take() } catch (e: InterruptedException) { break }
                if (chunk.generation != generation || !running) continue
                val item = current
                if (item == null || item.itemId != chunk.itemId || item.contentIndex != chunk.contentIndex) current = Item(chunk.itemId, chunk.contentIndex, framesWritten)
                lastLevel = pcmLevel(chunk.pcm)
                var offset = 0
                while (offset < chunk.pcm.size && running && chunk.generation == generation) {
                    val n = track.write(chunk.pcm, offset, chunk.pcm.size - offset)
                    if (n <= 0) break
                    offset += n
                    framesWritten += n / 2
                }
            }
        }, "call-speaker").apply { isDaemon = true; start() }
    }

    private fun routeToLoudspeaker() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = audio.availableCommunicationDevices
            val personal = devices.firstOrNull { it.type in PERSONAL_OUTPUTS }
            val target = personal ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            target?.let { runCatching { audio.setCommunicationDevice(it) } }
        } else {
            legacySpeakerphone(on = true)
        }
    }

    @Suppress("DEPRECATION")
    private fun legacySpeakerphone(on: Boolean) {
        val personal = audio.isWiredHeadsetOn || audio.isBluetoothScoOn || audio.isBluetoothA2dpOn
        audio.isSpeakerphoneOn = on && !personal
    }

    override fun play(pcm: ByteArray, itemId: String, contentIndex: Int) {
        if (running && pcm.isNotEmpty()) queue.put(Chunk(pcm, itemId, contentIndex, generation))
    }

    override fun interrupt(): CallSpeaker.Heard? {
        val wasBusy = busy()
        generation++
        queue.clear()
        val item = current
        val head = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        runCatching {
            track.pause()
            track.flush()
            track.play()
        }
        // The head restarts from zero after a flush; what was written is counted from there.
        val heard = item?.takeIf { wasBusy }?.let { CallSpeaker.Heard(it.itemId, it.contentIndex, ((head - it.startFrame).coerceAtLeast(0) * 1000) / RealtimeWire.SAMPLE_RATE) }
        framesWritten = 0
        current = null
        lastLevel = 0f
        return heard
    }

    override fun busy(): Boolean {
        val head = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        return queue.isNotEmpty() || head < framesWritten
    }

    override fun level(): Float = if (busy()) lastLevel else 0f

    override fun release() {
        running = false
        writer.interrupt()
        queue.clear()
        runCatching { track.stop() }
        track.release()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) runCatching { audio.clearCommunicationDevice() }
        else legacySpeakerphone(on = false)
        audio.mode = previousMode
    }

    private companion object {
        val PERSONAL_OUTPUTS = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            26, // TYPE_BLE_HEADSET (API 31)
        )
    }
}
