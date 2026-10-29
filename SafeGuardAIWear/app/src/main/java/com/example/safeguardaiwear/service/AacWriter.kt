package com.example.safeguardaiwear.service

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.Closeable
import java.io.File
import java.security.MessageDigest

/**
 * Encodes 16-bit mono PCM to AAC and saves it as an .m4a file.
 *
 * Android has no built-in MP3 encoder, so AAC (.m4a) is used instead. It plays
 * on Mac (QuickTime / Music), Windows, iPhone and Android, and is smaller than MP3
 * at the same quality.
 */
class AacWriter(
    private val file: File,
    private val sampleRate: Int
) : Closeable {

    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val info = MediaCodec.BufferInfo()

    private var track = -1
    private var muxerStarted = false
    private var samplesWritten = 0
    private var totalBytes = 0L

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
    }

    /** Presentation time (microseconds) of the next sample. */
    private fun ptsUs(): Long = totalBytes * 1_000_000L / (sampleRate * 2)

    fun write(buffer: ByteArray, length: Int) {
        var offset = 0
        while (offset < length) {
            val index = codec.dequeueInputBuffer(10_000)
            if (index >= 0) {
                val input = codec.getInputBuffer(index)
                if (input != null) {
                    input.clear()
                    val n = minOf(input.remaining(), length - offset)
                    input.put(buffer, offset, n)
                    codec.queueInputBuffer(index, 0, n, ptsUs(), 0)
                    totalBytes += n
                    offset += n
                }
            }
            drain(endOfStream = false)
        }
    }

    override fun close() {
        try {
            var index: Int
            var tries = 0
            do {
                index = codec.dequeueInputBuffer(10_000)
                if (index < 0) drain(endOfStream = false)
                tries++
            } while (index < 0 && tries < 50)
            if (index >= 0) {
                codec.queueInputBuffer(index, 0, 0, ptsUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            drain(endOfStream = true)
        } finally {
            try { codec.stop() } catch (_: Exception) { }
            codec.release()
            if (muxerStarted && samplesWritten > 0) {
                try { muxer.stop() } catch (_: Exception) { }
            }
            muxer.release()
        }

        if (samplesWritten == 0) {
            file.delete()
        } else {
            writeSha256Sidecar()
        }
    }

    private fun drain(endOfStream: Boolean) {
        var idle = 0
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    idle++
                    if (!endOfStream || idle > 100) return
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                }
                index >= 0 -> {
                    val output = codec.getOutputBuffer(index)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        info.size = 0
                    }
                    if (info.size > 0 && muxerStarted && output != null) {
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        muxer.writeSampleData(track, output, info)
                        samplesWritten++
                    }
                    codec.releaseOutputBuffer(index, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
            }
        }
    }

    /**
     * Saves a SHA-256 fingerprint next to the recording (recording_x.m4a.sha256).
     * If the audio is later altered, the hash no longer matches (tamper evidence).
     */
    private fun writeSha256Sidecar() {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        File(file.parentFile, file.name + ".sha256").writeText("$hex  ${file.name}\n")
    }
}