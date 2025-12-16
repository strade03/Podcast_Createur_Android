package com.podcastcreateur.app

import android.media.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class AudioContent(
    val data: ShortArray,
    val sampleRate: Int
)

object AudioHelper {
    private const val BIT_RATE = 128000
    private const val MAX_SAMPLES = 44100 * 60 * 10 // 10 minutes max en mémoire

    /**
     * Décode avec downsampling intelligent pour les gros fichiers
     */
    fun decodeToPCM(input: File, maxSamples: Int = MAX_SAMPLES): AudioContent {
        if (!input.exists()) return AudioContent(ShortArray(0), 44100)
        
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(input.absolutePath)
        } catch (e: Exception) {
            e.printStackTrace()
            return AudioContent(ShortArray(0), 44100)
        }

        var trackIndex = -1
        var format: MediaFormat? = null
        
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio/") == true) {
                trackIndex = i
                format = f
                break
            }
        }

        if (trackIndex < 0 || format == null) {
            extractor.release()
            return AudioContent(ShortArray(0), 44100)
        }

        val sourceSampleRate = try {
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } catch (e: Exception) {
            44100
        }

        // Estimer la durée pour calculer le downsampling
        val durationUs = try {
            format.getLong(MediaFormat.KEY_DURATION)
        } catch (e: Exception) {
            0L
        }
        
        val estimatedSamples = if (durationUs > 0) {
            ((durationUs / 1_000_000.0) * sourceSampleRate).toLong()
        } else {
            0L
        }

        // Calculer le facteur de downsampling si nécessaire
        val downsampleFactor = if (estimatedSamples > maxSamples) {
            (estimatedSamples / maxSamples).toInt().coerceAtLeast(1)
        } else {
            1
        }

        extractor.selectTrack(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: run {
            extractor.release()
            return AudioContent(ShortArray(0), 44100)
        }
        
        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(format, null, null, 0)
        decoder.start()

        val bufferInfo = MediaCodec.BufferInfo()
        val pcmData = java.io.ByteArrayOutputStream()
        
        var actualSampleRate = sourceSampleRate
        var channelCount = 1
        var sampleCounter = 0 // Pour le downsampling
        
        try {
            var isEOS = false
            while (true) {
                if (!isEOS) {
                    val inIndex = decoder.dequeueInputBuffer(10000)
                    if (inIndex >= 0) {
                        val inBuffer = decoder.getInputBuffer(inIndex)
                        if (inBuffer != null) {
                            val sampleSize = extractor.readSampleData(inBuffer, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                isEOS = true
                            } else {
                                decoder.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000)
                
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val outputFormat = decoder.outputFormat
                    actualSampleRate = try {
                        outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    } catch (e: Exception) {
                        sourceSampleRate
                    }
                    channelCount = try {
                        outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    } catch (e: Exception) {
                        1
                    }
                }
                
                if (outIndex >= 0) {
                    val outBuffer = decoder.getOutputBuffer(outIndex)
                    if (outBuffer != null && bufferInfo.size > 0) {
                        val chunk = ByteArray(bufferInfo.size)
                        outBuffer.get(chunk)
                        outBuffer.clear()
                        
                        // Appliquer le downsampling
                        if (downsampleFactor > 1) {
                            val downsampled = downsampleChunk(chunk, downsampleFactor, channelCount)
                            pcmData.write(downsampled)
                        } else {
                            pcmData.write(chunk)
                        }
                    }
                    decoder.releaseOutputBuffer(outIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (isEOS) break 
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { decoder.stop(); decoder.release() } catch(e: Exception) { e.printStackTrace() }
            try { extractor.release() } catch(e: Exception) { e.printStackTrace() }
        }

        val bytes = pcmData.toByteArray()
        val shorts = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        
        // Conversion stéréo -> mono si nécessaire
        val monoShorts = if (channelCount == 2) {
            convertStereoToMono(shorts)
        } else {
            shorts
        }
        
        // Ajuster le sample rate si on a fait du downsampling
        val finalSampleRate = if (downsampleFactor > 1) {
            actualSampleRate / downsampleFactor
        } else {
            actualSampleRate
        }
        
        return AudioContent(monoShorts, finalSampleRate)
    }

    /**
     * Downsampling d'un chunk de données PCM
     */
    private fun downsampleChunk(data: ByteArray, factor: Int, channels: Int): ByteArray {
        if (factor <= 1) return data
        
        val shorts = ShortArray(data.size / 2)
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        
        val samplesPerChannel = shorts.size / channels
        val outputSamplesPerChannel = samplesPerChannel / factor
        val output = ShortArray(outputSamplesPerChannel * channels)
        
        for (i in 0 until outputSamplesPerChannel) {
            for (ch in 0 until channels) {
                val srcIdx = (i * factor * channels) + ch
                if (srcIdx < shorts.size) {
                    output[i * channels + ch] = shorts[srcIdx]
                }
            }
        }
        
        val outputBytes = ByteArray(output.size * 2)
        ByteBuffer.wrap(outputBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(output)
        return outputBytes
    }

    private fun convertStereoToMono(stereo: ShortArray): ShortArray {
        val mono = ShortArray(stereo.size / 2)
        for (i in mono.indices) {
            val left = stereo[i * 2].toInt()
            val right = stereo[i * 2 + 1].toInt()
            mono[i] = ((left + right) / 2).toShort()
        }
        return mono
    }

    private fun resample(input: ShortArray, currentRate: Int, targetRate: Int): ShortArray {
        if (currentRate == targetRate) return input
        
        val ratio = currentRate.toDouble() / targetRate.toDouble()
        val outputSize = (input.size / ratio).toInt()
        val output = ShortArray(outputSize)

        for (i in 0 until outputSize) {
            val position = i * ratio
            val index = position.toInt()
            if (index >= input.size - 1) {
                output[i] = input[input.size - 1]
            } else {
                val fraction = position - index
                val val1 = input[index]
                val val2 = input[index + 1]
                output[i] = (val1 + fraction * (val2 - val1)).toInt().toShort()
            }
        }
        return output
    }

    fun savePCMToAAC(pcmData: ShortArray, outputFile: File, sampleRate: Int): Boolean {
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null

        try {
            val mime = MediaFormat.MIMETYPE_AUDIO_AAC
            val format = MediaFormat.createAudioFormat(mime, sampleRate, 1)
            format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)

            encoder = MediaCodec.createEncoderByType(mime)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var audioTrackIndex = -1
            var muxerStarted = false

            val outputBufferInfo = MediaCodec.BufferInfo()
            val byteBuffer = ByteBuffer.allocate(pcmData.size * 2)
            byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
            for (s in pcmData) byteBuffer.putShort(s)
            byteBuffer.position(0)
            val fullPcmBytes = byteBuffer.array()

            var inputOffset = 0
            var isEOS = false

            while (true) {
                if (!isEOS) {
                    val inIndex = encoder.dequeueInputBuffer(10000)
                    if (inIndex >= 0) {
                        val inBuffer = encoder.getInputBuffer(inIndex)
                        inBuffer?.clear()
                        val remaining = fullPcmBytes.size - inputOffset
                        val toRead = if (remaining > 4096) 4096 else remaining

                        if (toRead > 0) {
                            inBuffer?.put(fullPcmBytes, inputOffset, toRead)
                            inputOffset += toRead
                            val pts = (inputOffset.toLong() * 1000000L / (sampleRate * 2)).toLong()
                            encoder.queueInputBuffer(inIndex, 0, toRead, pts, 0)
                        } else {
                            encoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isEOS = true
                        }
                    }
                }

                val outIndex = encoder.dequeueOutputBuffer(outputBufferInfo, 10000)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = encoder.outputFormat
                    audioTrackIndex = muxer.addTrack(newFormat)
                    muxer.start()
                    muxerStarted = true
                } else if (outIndex >= 0) {
                    val encodedData = encoder.getOutputBuffer(outIndex)
                    if ((outputBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) outputBufferInfo.size = 0
                    if (outputBufferInfo.size != 0 && muxerStarted) {
                        encodedData?.position(outputBufferInfo.offset)
                        encodedData?.limit(outputBufferInfo.offset + outputBufferInfo.size)
                        muxer.writeSampleData(audioTrackIndex, encodedData!!, outputBufferInfo)
                    }
                    encoder.releaseOutputBuffer(outIndex, false)
                    if ((outputBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        } finally {
            try { encoder?.stop(); encoder?.release() } catch(e: Exception) { e.printStackTrace() }
            try { muxer?.stop(); muxer?.release() } catch(e: Exception) { e.printStackTrace() }
        }
    }

    fun mergeFiles(inputs: List<File>, output: File): Boolean {
        if (inputs.isEmpty()) return false
        try {
            val allSamples = ArrayList<Short>()
            var masterSampleRate = 44100
            var isFirst = true
            
            for (file in inputs) {
                // Pour la fusion, on charge tout (mais on pourrait aussi optimiser ici)
                val content = decodeToPCM(file, Int.MAX_VALUE)
                
                if (isFirst) {
                    masterSampleRate = content.sampleRate
                    for (s in content.data) allSamples.add(s)
                    isFirst = false
                } else {
                    if (content.sampleRate != masterSampleRate) {
                        val resampledData = resample(content.data, content.sampleRate, masterSampleRate)
                        for (s in resampledData) allSamples.add(s)
                    } else {
                        for (s in content.data) allSamples.add(s)
                    }
                }
            }
            
            val finalData = ShortArray(allSamples.size)
            for (i in allSamples.indices) finalData[i] = allSamples[i]
            
            return savePCMToAAC(finalData, output, masterSampleRate)
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }
}