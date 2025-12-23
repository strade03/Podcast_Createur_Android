package com.podcastcreateur.app

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

object TranscodeUtils {
    private const val BIT_RATE = 128000
    private const val BATCH_SIZE = 4096 // nombre max d'échantillons traités par batch

    /**
     * Version optimisée de runTranscode avec traitement par batch (moins d'allocations).
     */
    fun runTranscodeWithBatch(
        input: File,
        output: File,
        processSample: (Long, Short) -> Short?
    ): Boolean {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        try {
            extractor = MediaExtractor()
            extractor.setDataSource(input.absolutePath)
            val trackIdx = selectAudioTrack(extractor)
            if (trackIdx < 0) return false

            extractor.selectTrack(trackIdx)
            val format = extractor.getTrackFormat(trackIdx)
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()

            val encFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels)
            encFormat.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            encFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            encFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val encBufferInfo = MediaCodec.BufferInfo()
            val decBufferInfo = MediaCodec.BufferInfo()
            var muxerTrackIndex = -1
            var totalSamplesProcessed = 0L
            var isInputEOS = false
            var isDecodedEOS = false

            // Buffers réutilisables
            val tempShorts = ShortArray(BATCH_SIZE)
            val tempByteBuffer = ByteBuffer.allocateDirect(BATCH_SIZE * 2).order(ByteOrder.LITTLE_ENDIAN)

            while (!isDecodedEOS) {
                if (!isInputEOS) {
                    val inIdx = decoder.dequeueInputBuffer(1000)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx)!!
                        val sz = extractor.readSampleData(buf, 0)
                        if (sz < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isInputEOS = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                var outIdx = decoder.dequeueOutputBuffer(decBufferInfo, 1000)
                while (outIdx >= 0) {
                    val outBuf = decoder.getOutputBuffer(outIdx)
                    if (outBuf != null && decBufferInfo.size > 0) {
                        val chunkBytes = ByteArray(decBufferInfo.size)
                        outBuf.position(decBufferInfo.offset)
                        outBuf.get(chunkBytes)
                        val shortBuffer = ByteBuffer.wrap(chunkBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()

                        val outputStream = ByteArrayOutputStream()
                        var batchWriteIdx = 0

                        while (shortBuffer.hasRemaining()) {
                            val sample = shortBuffer.get()
                            val newSample = processSample(totalSamplesProcessed, sample)
                            totalSamplesProcessed++

                            if (newSample != null) {
                                tempShorts[batchWriteIdx++] = newSample
                                if (batchWriteIdx == BATCH_SIZE) {
                                    // Flush batch
                                    tempByteBuffer.clear()
                                    tempByteBuffer.asShortBuffer().put(tempShorts, 0, batchWriteIdx)
                                    outputStream.write(tempByteBuffer.array(), 0, batchWriteIdx * 2)
                                    batchWriteIdx = 0
                                }
                            }
                        }

                        // Flush remaining
                        if (batchWriteIdx > 0) {
                            tempByteBuffer.clear()
                            tempByteBuffer.asShortBuffer().put(tempShorts, 0, batchWriteIdx)
                            outputStream.write(tempByteBuffer.array(), 0, batchWriteIdx * 2)
                        }

                        val processedBytes = outputStream.toByteArray()
                        if (processedBytes.isNotEmpty()) {
                            feedEncoder(encoder, processedBytes)
                        }
                    }

                    decoder.releaseOutputBuffer(outIdx, false)
                    isDecodedEOS = (decBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    if (isDecodedEOS) {
                        val inIdx = encoder.dequeueInputBuffer(1000)
                        if (inIdx >= 0) {
                            encoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        }
                    }
                    outIdx = decoder.dequeueOutputBuffer(decBufferInfo, 1000)
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
                            val encodedData = encoder.getOutputBuffer(encOutIdx)!!
                            if (encBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                encBufferInfo.size = 0
                            }
                            if (encBufferInfo.size != 0 && muxerStarted) {
                                encodedData.position(encBufferInfo.offset)
                                encodedData.limit(encBufferInfo.offset + encBufferInfo.size)
                                muxer.writeSampleData(muxerTrackIndex, encodedData, encBufferInfo)
                            }
                            encoder.releaseOutputBuffer(encOutIdx, false)
                            if (encBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                    }
                    encOutIdx = encoder.dequeueOutputBuffer(encBufferInfo, 1000)
                }
            }
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        } finally {
            try { decoder?.stop(); decoder?.release() } catch (ignored: Exception) {}
            try { encoder?.stop(); encoder?.release() } catch (ignored: Exception) {}
            try { if (muxer != null && muxerStarted) muxer.stop(); muxer?.release() } catch (ignored: Exception) {}
            try { extractor?.release() } catch (ignored: Exception) {}
        }
    }

    private fun feedEncoder(encoder: MediaCodec, data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            val inIdx = encoder.dequeueInputBuffer(2000)
            if (inIdx >= 0) {
                val buf = encoder.getInputBuffer(inIdx)!!
                val toWrite = min(buf.remaining(), data.size - offset)
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