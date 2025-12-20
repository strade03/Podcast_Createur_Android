package com.podcastcreateur.app

import android.media.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

// ============================================
// DATA CLASSES
// ============================================

data class AudioContent(
    val data: ShortArray,
    val sampleRate: Int
)

data class AudioMetadata(
    val sampleRate: Int,
    val channelCount: Int,
    val duration: Long, // en millisecondes
    val totalSamples: Long
)

// ============================================
// AUDIO HELPER OBJECT
// ============================================

object AudioHelper {
    private const val BIT_RATE = 128000
    private const val CHUNK_SIZE = 8192

    // ============================================
    // NOUVELLES FONCTIONS OPTIMISÉES
    // ============================================

    /**
     * ✅ NOUVELLE : Récupère uniquement les métadonnées (RAPIDE)
     */
    fun getAudioMetadata(input: File): AudioMetadata? {
        if (!input.exists()) return null
        
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(input.absolutePath)
            
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
            
            if (trackIndex < 0 || format == null) return null
            
            val sampleRate = try {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } catch (e: Exception) { 44100 }
            
            val channelCount = try {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } catch (e: Exception) { 1 }
            
            val duration = try {
                format.getLong(MediaFormat.KEY_DURATION) / 1000 // microsecondes -> millisecondes
            } catch (e: Exception) { 0L }
            
            val totalSamples = (duration * sampleRate) / 1000
            
            return AudioMetadata(sampleRate, channelCount, duration, totalSamples)
            
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            extractor.release()
        }
    }

    /**
     * ✅ NOUVELLE : Génère waveform downsamplée (ne charge PAS tout le fichier)
     */
    fun generateWaveformData(
        input: File,
        targetWidth: Int,
        onProgress: (Float) -> Unit = {}
    ): FloatArray {
        if (!input.exists()) return FloatArray(0)
        
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(input.absolutePath)
            
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
            
            if (trackIndex < 0 || format == null) return FloatArray(0)
            
            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return FloatArray(0)
            
            val decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()
            
            val waveform = FloatArray(targetWidth)
            val bufferInfo = MediaCodec.BufferInfo()
            
            val duration = try {
                format.getLong(MediaFormat.KEY_DURATION)
            } catch (e: Exception) { 1000000L }
            
            var pixelIndex = 0
            var sampleSum = 0.0
            var sampleCount = 0
            val samplesPerPixel = ((duration / 1000) * 44.1).toInt() / targetWidth
            
            var isInputDone = false
            var processedSamples = 0L
            val totalApproxSamples = (duration / 1000000.0 * 44100).toLong()
            
            while (pixelIndex < targetWidth) {
                // Feed input
                if (!isInputDone) {
                    val inIndex = decoder.dequeueInputBuffer(10000)
                    if (inIndex >= 0) {
                        val inBuffer = decoder.getInputBuffer(inIndex)
                        if (inBuffer != null) {
                            val sampleSize = extractor.readSampleData(inBuffer, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(
                                    inIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                isInputDone = true
                            } else {
                                decoder.queueInputBuffer(
                                    inIndex, 0, sampleSize,
                                    extractor.sampleTime, 0
                                )
                                extractor.advance()
                            }
                        }
                    }
                }
                
                // Get output
                val outIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000)
                if (outIndex >= 0) {
                    val outBuffer = decoder.getOutputBuffer(outIndex)
                    if (outBuffer != null && bufferInfo.size > 0) {
                        outBuffer.order(ByteOrder.LITTLE_ENDIAN)
                        
                        while (outBuffer.hasRemaining() && pixelIndex < targetWidth) {
                            val sample = outBuffer.short / 32768.0
                            sampleSum += sample * sample
                            sampleCount++
                            processedSamples++
                            
                            if (sampleCount >= samplesPerPixel) {
                                waveform[pixelIndex] = sqrt(sampleSum / sampleCount).toFloat()
                                pixelIndex++
                                sampleSum = 0.0
                                sampleCount = 0
                                
                                if (totalApproxSamples > 0) {
                                    onProgress(processedSamples.toFloat() / totalApproxSamples)
                                }
                            }
                        }
                    }
                    decoder.releaseOutputBuffer(outIndex, false)
                    
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        break
                    }
                } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (isInputDone) break
                }
            }
            
            if (sampleCount > 0 && pixelIndex < targetWidth) {
                waveform[pixelIndex] = sqrt(sampleSum / sampleCount).toFloat()
            }
            
            decoder.stop()
            decoder.release()
            extractor.release()
            
            onProgress(1.0f)
            return waveform
            
        } catch (e: Exception) {
            e.printStackTrace()
            extractor.release()
            return FloatArray(0)
        }
    }

    /**
     * ✅ NOUVELLE : Coupe audio SANS charger tout en mémoire
     */
    fun trimAudio(
        inputFile: File,
        outputFile: File,
        startMs: Long,
        endMs: Long,
        sampleRate: Int
    ): Boolean {
        val extractor = MediaExtractor()
        val muxer = MediaMuxer(
            outputFile.absolutePath,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        )
        
        try {
            extractor.setDataSource(inputFile.absolutePath)
            
            var trackIndex = -1
            var trackFormat: MediaFormat? = null
            
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    trackFormat = format
                    break
                }
            }
            
            if (trackIndex == -1 || trackFormat == null) return false
            
            extractor.selectTrack(trackIndex)
            extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            
            val muxerTrackIndex = muxer.addTrack(trackFormat)
            muxer.start()
            
            val buffer = ByteBuffer.allocate(256 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()
            
            while (true) {
                val sampleTime = extractor.sampleTime / 1000 // µs -> ms
                if (sampleTime > endMs || sampleTime < 0) break
                
                bufferInfo.size = extractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) break
                
                bufferInfo.presentationTimeUs = extractor.sampleTime
                bufferInfo.flags = extractor.sampleFlags
                
                muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                extractor.advance()
            }
            
            muxer.stop()
            return true
            
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        } finally {
            extractor.release()
            muxer.release()
        }
    }

    /**
     * ✅ NOUVELLE : Normalisation en 2 passes PAR CHUNKS
     */
    fun normalizeAudio(
        inputFile: File,
        outputFile: File,
        startMs: Long,
        endMs: Long,
        sampleRate: Int,
        targetPeak: Float = 0.95f,
        onProgress: (Float) -> Unit = {}
    ): Boolean {
        // PASSE 1 : Trouver le peak max
        onProgress(0f)
        val maxPeak = findMaxPeakInRange(inputFile, startMs, endMs) { progress ->
            onProgress(progress * 0.5f)
        }
        
        if (maxPeak == 0f) return false
        
        val gain = targetPeak / maxPeak
        
        // PASSE 2 : Appliquer le gain
        return applyGainToFile(inputFile, outputFile, gain, startMs, endMs, sampleRate) { progress ->
            onProgress(0.5f + progress * 0.5f)
        }
    }

    private fun findMaxPeakInRange(
        file: File,
        startMs: Long,
        endMs: Long,
        onProgress: (Float) -> Unit
    ): Float {
        val extractor = MediaExtractor()
        var maxPeak = 0f
        
        try {
            extractor.setDataSource(file.absolutePath)
            
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
            
            if (trackIndex < 0 || format == null) return 0f
            
            extractor.selectTrack(trackIndex)
            extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return 0f
            val decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()
            
            val bufferInfo = MediaCodec.BufferInfo()
            var isInputDone = false
            var processedSamples = 0L
            val totalSamples = ((endMs - startMs) * 44.1).toLong()
            
            while (true) {
                if (!isInputDone) {
                    val inIndex = decoder.dequeueInputBuffer(10000)
                    if (inIndex >= 0) {
                        val inBuffer = decoder.getInputBuffer(inIndex)
                        if (inBuffer != null) {
                            val sampleSize = extractor.readSampleData(inBuffer, 0)
                            val sampleTime = extractor.sampleTime / 1000
                            
                            if (sampleSize < 0 || sampleTime > endMs) {
                                decoder.queueInputBuffer(
                                    inIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                isInputDone = true
                            } else {
                                decoder.queueInputBuffer(
                                    inIndex, 0, sampleSize,
                                    extractor.sampleTime, 0
                                )
                                extractor.advance()
                            }
                        }
                    }
                }
                
                val outIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000)
                if (outIndex >= 0) {
                    val outBuffer = decoder.getOutputBuffer(outIndex)
                    if (outBuffer != null && bufferInfo.size > 0) {
                        outBuffer.order(ByteOrder.LITTLE_ENDIAN)
                        
                        while (outBuffer.hasRemaining()) {
                            val sample = abs(outBuffer.short / 32768f)
                            if (sample > maxPeak) maxPeak = sample
                            processedSamples++
                        }
                        
                        if (processedSamples % 10000 == 0L) {
                            onProgress(processedSamples.toFloat() / totalSamples)
                        }
                    }
                    decoder.releaseOutputBuffer(outIndex, false)
                    
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        break
                    }
                } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (isInputDone) break
                }
            }
            
            decoder.stop()
            decoder.release()
            
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            extractor.release()
        }
        
        onProgress(1f)
        return maxPeak
    }

    private fun applyGainToFile(
        inputFile: File,
        outputFile: File,
        gain: Float,
        startMs: Long,
        endMs: Long,
        sampleRate: Int,
        onProgress: (Float) -> Unit
    ): Boolean {
        // Pour simplifier, on utilise decodeToPCM + modifications
        // Version complète avec pipeline streaming serait plus complexe
        try {
            val content = decodeToPCM(inputFile)
            val startSample = ((startMs * sampleRate) / 1000).toInt()
            val endSample = ((endMs * sampleRate) / 1000).toInt().coerceAtMost(content.data.size)
            
            // Applique le gain
            for (i in startSample until endSample) {
                if (i < content.data.size) {
                    val amplified = (content.data[i] * gain).toInt()
                    content.data[i] = amplified.coerceIn(-32768, 32767).toShort()
                }
                
                if (i % 10000 == 0) {
                    onProgress((i - startSample).toFloat() / (endSample - startSample))
                }
            }
            
            onProgress(1f)
            return savePCMToAAC(content.data, outputFile, sampleRate)
            
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    // ============================================
    // ANCIENNES FONCTIONS (GARDÉES POUR COMPATIBILITÉ)
    // ============================================

    /**
     * Décode le fichier et renvoie les données MONO avec leur fréquence d'origine.
     * ⚠️ GARDE pour compatibilité (lecture audio, merge)
     */
    fun decodeToPCM(input: File): AudioContent {
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
        
        try {
            var isEOS = false
            while (true) {
                if (!isEOS) {
                    val inIndex = decoder.dequeueInputBuffer(1000)
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

                val outIndex = decoder.dequeueOutputBuffer(bufferInfo, 1000)
                
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
                        pcmData.write(chunk)
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
        
        val monoShorts = if (channelCount == 2) {
            convertStereoToMono(shorts)
        } else {
            shorts
        }
        
        return AudioContent(monoShorts, actualSampleRate)
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
                    val inIndex = encoder.dequeueInputBuffer(1000)
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

                val outIndex = encoder.dequeueOutputBuffer(outputBufferInfo, 1000)
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
                val content = decodeToPCM(file)
                
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