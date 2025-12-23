package com.podcastcreateur.app

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import android.media.MediaCodecInfo

data class AudioMetadata(
    val sampleRate: Int,
    val channelCount: Int,
    val duration: Long,
    val totalSamples: Long
)

object AudioHelper {
    private const val BIT_RATE = 128000

    fun getAudioMetadata(input: File): AudioMetadata? {
        if (!input.exists()) return null
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(input.absolutePath)
            val trackIdx = selectAudioTrack(extractor)
            if (trackIdx < 0) return null
            val format = extractor.getTrackFormat(trackIdx)
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durationUs = format.getLong(MediaFormat.KEY_DURATION)
            val durationMs = durationUs / 1000
            val totalSamples = (durationMs * sampleRate * channels) / 1000
            AudioMetadata(sampleRate, channels, durationMs, totalSamples)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            extractor.release()
        }
    }

    /**
     * SCAN RAPIDE : Trouve le volume max sans écrire de fichier.
     */
    fun calculatePeak(inputFile: File): Float {
        var maxPeakFound = 0f
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        return try {
            extractor.setDataSource(inputFile.absolutePath)
            val trackIdx = selectAudioTrack(extractor)
            if (trackIdx < 0) return 0f
            extractor.selectTrack(trackIdx)
            val format = extractor.getTrackFormat(trackIdx)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return 0f
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()
            val bufferInfo = MediaCodec.BufferInfo()
            var isEOS = false
            while (!isEOS) {
                val inIdx = decoder.dequeueInputBuffer(2000)
                if (inIdx >= 0) {
                    val buf = decoder.getInputBuffer(inIdx)
                    val sz = extractor.readSampleData(buf!!, 0)
                    if (sz < 0) {
                        decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        isEOS = true
                    } else {
                        decoder.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
                var outIdx = decoder.dequeueOutputBuffer(bufferInfo, 2000)
                while (outIdx >= 0) {
                    val outBuf = decoder.getOutputBuffer(outIdx)
                    if (outBuf != null && bufferInfo.size > 0) {
                        val shorts = outBuf.asShortBuffer()
                        // On scanne par saut de 100 → accélère sans perte significative
                        var i = 0
                        while (i < shorts.remaining()) {
                            val sample = abs(shorts.get(i).toFloat() / 32768f)
                            if (sample > maxPeakFound) maxPeakFound = sample
                            i += 100
                        }
                    }
                    decoder.releaseOutputBuffer(outIdx, false)
                    outIdx = decoder.dequeueOutputBuffer(bufferInfo, 2000)
                }
            }
            maxPeakFound
        } catch (e: Exception) {
            0f
        } finally {
            try { decoder?.stop(); decoder?.release() } catch (ignored: Exception) {}
            extractor.release()
        }
    }

    /**
     * SAUVEGARDE FINALE : Applique Coupes + Gain en 1 seule passe (optimisée).
     */
    fun saveWithCutsAndGain(input: File, output: File, cutRanges: List<Pair<Long, Long>>, gain: Float): Boolean {
        val sortedCuts = cutRanges.sortedBy { it.first }
        val applyGain = gain > 1.01f || gain < 0.99f
        return TranscodeUtils.runTranscodeWithBatch(input, output) { sampleIndex, sampleValue ->
            // 1. Gestion des coupes
            var shouldKeep = true
            for (range in sortedCuts) {
                if (sampleIndex in range.first until range.second) {
                    shouldKeep = false
                    break
                }
                if (sampleIndex < range.first) break
            }
            if (!shouldKeep) {
                null
            } else {
                // 2. Gestion du gain
                if (applyGain) {
                    (sampleValue * gain).toInt().coerceIn(-32768, 32767).toShort()
                } else {
                    sampleValue
                }
            }
        }
    }

    fun deleteRegionStreaming(input: File, output: File, startSample: Int, endSample: Int): Boolean {
        return TranscodeUtils.runTranscodeWithBatch(input, output) { sampleIndex, sampleValue ->
            if (sampleIndex in startSample until endSample) null else sampleValue
        }
    }

    fun mergeFiles(inputs: List<File>, output: File): Boolean {
        if (inputs.isEmpty()) return false
        var sampleRate = 44100
        var channels = 1
        val scanEx = MediaExtractor()
        try {
            scanEx.setDataSource(inputs[0].absolutePath)
            val idx = selectAudioTrack(scanEx)
            if (idx >= 0) {
                val f = scanEx.getTrackFormat(idx)
                sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            }
        } catch (ignored: Exception) {
        } finally {
            scanEx.release()
        }

        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels)
        format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerTrackIndex = -1
        var muxerStarted = false
        val encBufferInfo = MediaCodec.BufferInfo()

        try {
            for (input in inputs) {
                if (!input.exists()) continue
                val extractor = MediaExtractor()
                extractor.setDataSource(input.absolutePath)
                val trackIdx = selectAudioTrack(extractor)
                if (trackIdx < 0) {
                    extractor.release()
                    continue
                }
                extractor.selectTrack(trackIdx)
                val decFormat = extractor.getTrackFormat(trackIdx)
                val decoder = MediaCodec.createDecoderByType(decFormat.getString(MediaFormat.KEY_MIME)!!)
                decoder.configure(decFormat, null, null, 0)
                decoder.start()

                val bufferInfo = MediaCodec.BufferInfo()
                var isEOS = false

                while (!isEOS) {
                    val inIdx = decoder.dequeueInputBuffer(2000)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx)
                        val sz = extractor.readSampleData(buf!!, 0)
                        if (sz < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isEOS = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }

                    var outIdx = decoder.dequeueOutputBuffer(bufferInfo, 2000)
                    while (outIdx >= 0) {
                        val outBuf = decoder.getOutputBuffer(outIdx)
                        if (outBuf != null && bufferInfo.size > 0) {
                            val chunk = ByteArray(bufferInfo.size)
                            outBuf.position(bufferInfo.offset)
                            outBuf.get(chunk)
                            feedEncoder(encoder, chunk)
                        }
                        decoder.releaseOutputBuffer(outIdx, false)
                        outIdx = decoder.dequeueOutputBuffer(bufferInfo, 2000)
                    }

                    var encOutIdx = encoder.dequeueOutputBuffer(encBufferInfo, 1000)
                    while (encOutIdx >= 0 || encOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        when (encOutIdx) {
                            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                muxerTrackIndex = muxer.addTrack(encoder.outputFormat)
                                muxer.start()
                                muxerStarted = true
                            }
                            in 0..Int.MAX_VALUE -> {
                                val encodedData = encoder.getOutputBuffer(encOutIdx)
                                if (encBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) encBufferInfo.size = 0
                                if (encBufferInfo.size != 0 && muxerStarted) {
                                    encodedData?.position(encBufferInfo.offset)
                                    encodedData?.limit(encBufferInfo.offset + encBufferInfo.size)
                                    muxer.writeSampleData(muxerTrackIndex, encodedData!!, encBufferInfo)
                                }
                                encoder.releaseOutputBuffer(encOutIdx, false)
                                if (encBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                            }
                        }
                        encOutIdx = encoder.dequeueOutputBuffer(encBufferInfo, 1000)
                    }
                }
                decoder.stop(); decoder.release(); extractor.release()
            }

            val inIdx = encoder.dequeueInputBuffer(2000)
            if (inIdx >= 0) encoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)

            var encOutIdx = encoder.dequeueOutputBuffer(encBufferInfo, 1000)
            while (encOutIdx >= 0) {
                if (encOutIdx >= 0) {
                    val encodedData = encoder.getOutputBuffer(encOutIdx)
                    if (encBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) encBufferInfo.size = 0
                    if (encBufferInfo.size != 0 && muxerStarted) {
                        encodedData?.position(encBufferInfo.offset)
                        encodedData?.limit(encBufferInfo.offset + encBufferInfo.size)
                        muxer.writeSampleData(muxerTrackIndex, encodedData!!, encBufferInfo)
                    }
                    encoder.releaseOutputBuffer(encOutIdx, false)
                }
                encOutIdx = encoder.dequeueOutputBuffer(encBufferInfo, 1000)
            }

            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        } finally {
            try { encoder.stop(); encoder.release() } catch (ignored: Exception) {}
            try { if (muxerStarted) muxer.stop(); muxer.release() } catch (ignored: Exception) {}
        }
    }

    private fun feedEncoder(encoder: MediaCodec, data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            val inIdx = encoder.dequeueInputBuffer(2000)
            if (inIdx >= 0) {
                val buf = encoder.getInputBuffer(inIdx)!!
                val remaining = data.size - offset
                val toWrite = if (remaining > buf.capacity()) buf.capacity() else remaining
                buf.clear()
                buf.put(data, offset, toWrite)
                encoder.queueInputBuffer(inIdx, 0, toWrite, System.nanoTime() / 1000, 0)
                offset += toWrite
            }
        }
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) return i
        }
        return -1
    }
}