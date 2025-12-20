package com.podcastcreateur.app

import android.media.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

data class AudioMetadata(
    val sampleRate: Int,
    val channelCount: Int,
    val duration: Long, // en millisecondes
    val totalSamples: Long
)

data class AudioContent(
    val data: ShortArray,
    val sampleRate: Int
)

object AudioHelper {
    private const val BIT_RATE = 128000
    
    // Pour l'affichage : on garde 1 point pour X samples.
    // À 44100Hz, si on garde 1 point tous les 882 samples, on a 50 points par seconde.
    // C'est très fluide et permet un zoom précis.
    private const val SAMPLES_PER_POINT = 882 

    /**
     * Récupère rapidement les métadonnées
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
            
            val sampleRate = try { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) } catch (e: Exception) { 44100 }
            val channelCount = try { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) } catch (e: Exception) { 1 }
            val duration = try { format.getLong(MediaFormat.KEY_DURATION) / 1000 } catch (e: Exception) { 0L }
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
     * Génère la waveform en mode "Streaming".
     * @param onUpdate Appelé régulièrement avec les nouvelles données ajoutées.
     */
    fun loadWaveformStream(
        input: File,
        onUpdate: (FloatArray) -> Unit
    ) {
        if (!input.exists()) {
            onUpdate(FloatArray(0))
            return
        }
        
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
            
            if (trackIndex < 0 || format == null) return
            
            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return
            
            val decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()
            
            val bufferInfo = MediaCodec.BufferInfo()
            val tempBuffer = ArrayList<Float>() // Tampon pour envoyer des blocs à l'UI
            var maxPeakInChunk = 0f
            var sampleCountInChunk = 0
            
            var isInputDone = false
            
            // On envoie une mise à jour à l'UI tous les X points générés pour fluidifier
            val updateThreshold = 200 // Envoyer tous les 200 points (approx 4 sec d'audio)

            while (true) {
                if (!isInputDone) {
                    val inIndex = decoder.dequeueInputBuffer(5000)
                    if (inIndex >= 0) {
                        val inBuffer = decoder.getInputBuffer(inIndex)
                        if (inBuffer != null) {
                            val sampleSize = extractor.readSampleData(inBuffer, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                isInputDone = true
                            } else {
                                decoder.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }
                
                val outIndex = decoder.dequeueOutputBuffer(bufferInfo, 5000)
                if (outIndex >= 0) {
                    val outBuffer = decoder.getOutputBuffer(outIndex)
                    if (outBuffer != null && bufferInfo.size > 0) {
                        outBuffer.order(ByteOrder.LITTLE_ENDIAN)
                        val shorts = outBuffer.asShortBuffer()
                        
                        while (shorts.hasRemaining()) {
                            // On prend la valeur ABSOLUE (Peak) pour ressembler à Audacity
                            val sample = abs(shorts.get().toFloat() / 32768f)
                            
                            if (sample > maxPeakInChunk) maxPeakInChunk = sample
                            sampleCountInChunk++
                            
                            // Downsampling : on garde le MAX trouvé dans le bloc de SAMPLES_PER_POINT
                            if (sampleCountInChunk >= SAMPLES_PER_POINT) {
                                tempBuffer.add(maxPeakInChunk)
                                maxPeakInChunk = 0f
                                sampleCountInChunk = 0
                                
                                // Envoyer à l'UI par paquets
                                if (tempBuffer.size >= updateThreshold) {
                                    onUpdate(tempBuffer.toFloatArray())
                                    tempBuffer.clear()
                                }
                            }
                        }
                    }
                    decoder.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (isInputDone) break
                }
            }
            
            // Envoyer le reste
            if (tempBuffer.isNotEmpty()) {
                onUpdate(tempBuffer.toFloatArray())
            }
            
            decoder.stop()
            decoder.release()
            
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            extractor.release()
        }
    }

    // ==========================================================
    // FONCTIONS EXISTANTES (Coupe, Normalisation, Merge)
    // ==========================================================

    fun decodeToPCM(input: File): AudioContent {
        if (!input.exists()) return AudioContent(ShortArray(0), 44100)
        val extractor = MediaExtractor()
        try { extractor.setDataSource(input.absolutePath) } catch (e: Exception) { return AudioContent(ShortArray(0), 44100) }
        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio/") == true) { trackIndex = i; format = f; break }
        }
        if (trackIndex < 0 || format == null) return AudioContent(ShortArray(0), 44100)
        extractor.selectTrack(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return AudioContent(ShortArray(0), 44100)
        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(format, null, null, 0)
        decoder.start()
        val bufferInfo = MediaCodec.BufferInfo()
        val pcmData = java.io.ByteArrayOutputStream()
        var actualSampleRate = 44100
        var isEOS = false
        while (true) {
            if (!isEOS) {
                val inIndex = decoder.dequeueInputBuffer(1000)
                if (inIndex >= 0) {
                    val inBuffer = decoder.getInputBuffer(inIndex)
                    val sampleSize = extractor.readSampleData(inBuffer!!, 0)
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        isEOS = true
                    } else {
                        decoder.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIndex = decoder.dequeueOutputBuffer(bufferInfo, 1000)
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                actualSampleRate = decoder.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            }
            if (outIndex >= 0) {
                val outBuffer = decoder.getOutputBuffer(outIndex)
                if (outBuffer != null && bufferInfo.size > 0) {
                    val chunk = ByteArray(bufferInfo.size)
                    outBuffer.get(chunk); outBuffer.clear(); pcmData.write(chunk)
                }
                decoder.releaseOutputBuffer(outIndex, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
            } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER && isEOS) break
        }
        decoder.release(); extractor.release()
        val bytes = pcmData.toByteArray()
        val shorts = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        return AudioContent(shorts, actualSampleRate)
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
            var trackIndex = -1; var muxerStarted = false
            val bufferInfo = MediaCodec.BufferInfo()
            val byteBuffer = ByteBuffer.allocate(pcmData.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (s in pcmData) byteBuffer.putShort(s)
            byteBuffer.position(0)
            val fullBytes = byteBuffer.array()
            var offset = 0; var isEOS = false
            while (true) {
                if (!isEOS) {
                    val inIndex = encoder.dequeueInputBuffer(1000)
                    if (inIndex >= 0) {
                        val remaining = fullBytes.size - offset
                        val toRead = if (remaining > 4096) 4096 else remaining
                        if (toRead > 0) {
                            val inBuffer = encoder.getInputBuffer(inIndex)
                            inBuffer?.put(fullBytes, offset, toRead)
                            offset += toRead
                            encoder.queueInputBuffer(inIndex, 0, toRead, System.nanoTime()/1000, 0)
                        } else {
                            encoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isEOS = true
                        }
                    }
                }
                val outIndex = encoder.dequeueOutputBuffer(bufferInfo, 1000)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    trackIndex = muxer.addTrack(encoder.outputFormat)
                    muxer.start(); muxerStarted = true
                } else if (outIndex >= 0) {
                    val data = encoder.getOutputBuffer(outIndex)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) bufferInfo.size = 0
                    if (bufferInfo.size != 0 && muxerStarted) {
                        data?.position(bufferInfo.offset); data?.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, data!!, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            return true
        } catch (e: Exception) { e.printStackTrace(); return false }
        finally { try{ encoder?.release(); muxer?.release() } catch(e:Exception){} }
    }

    fun normalizeAudio(inputFile: File, outputFile: File, startMs: Long, endMs: Long, sampleRate: Int, targetPeak: Float, onProgress: (Float)->Unit): Boolean {
        try {
            val content = decodeToPCM(inputFile)
            onProgress(0.5f)
            // Trouver max
            var maxVal = 0f
            val startIdx = ((startMs * sampleRate)/1000).toInt()
            val endIdx = ((endMs * sampleRate)/1000).toInt().coerceAtMost(content.data.size)
            for(i in startIdx until endIdx) {
                val v = abs(content.data[i].toFloat() / 32768f)
                if(v > maxVal) maxVal = v
            }
            if(maxVal == 0f) return false
            val gain = targetPeak / maxVal
            for(i in startIdx until endIdx) {
                val newVal = (content.data[i] * gain).toInt().coerceIn(-32768, 32767)
                content.data[i] = newVal.toShort()
            }
            onProgress(0.8f)
            return savePCMToAAC(content.data, outputFile, sampleRate)
        } catch(e: Exception) { return false }
    }
    
    fun mergeFiles(inputs: List<File>, output: File): Boolean {
        if (inputs.isEmpty()) return false
        val allData = ArrayList<Short>()
        var sr = 44100
        inputs.forEach { 
            val c = decodeToPCM(it)
            sr = c.sampleRate
            for(s in c.data) allData.add(s)
        }
        val arr = ShortArray(allData.size) { allData[it] }
        return savePCMToAAC(arr, output, sr)
    }
}