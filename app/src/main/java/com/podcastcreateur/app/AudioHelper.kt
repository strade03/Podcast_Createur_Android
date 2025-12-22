package com.podcastcreateur.app

import android.media.*
import android.util.Log
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

data class AudioProjectData(
    val rawFile: File,   // Le fichier audio décompressé (PCM 16bit Mono)
    val peaks: FloatArray, // Les données pour dessiner l'onde
    val sampleRate: Int,
    val durationMs: Long
)

object AudioHelper {
    const val POINTS_PER_SECOND = 50 // Précision de l'affichage

    /**
     * C'est la fonction CLÉ. Elle transforme n'importe quel MP3/M4A en :
     * 1. Un fichier RAW sur le disque (pour la lecture rapide et l'édition)
     * 2. Un tableau de Peaks (pour l'affichage instantané)
     */
    fun prepareProject(inputFile: File, projectDir: File, onProgress: (Int) -> Unit): AudioProjectData? {
        val rawFile = File(projectDir, "working_audio.raw")
        val cachePeaks = File(projectDir, "waveform.peaks")
        
        // Si les fichiers existent déjà, on charge le cache (Ouverture instantanée)
        if (rawFile.exists() && rawFile.length() > 0 && cachePeaks.exists()) {
            return try {
                val bytes = cachePeaks.readBytes()
                val floats = FloatArray(bytes.size / 4)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
                // On récupère le sampleRate "standard"
                AudioProjectData(rawFile, floats, 44100, (rawFile.length() / 2 / 44.1).toLong())
            } catch (e: Exception) {
                // Si erreur cache, on recrée
                convertFileToRaw(inputFile, rawFile, onProgress)
            }
        }

        return convertFileToRaw(inputFile, rawFile, onProgress)
    }

    private fun convertFileToRaw(inputFile: File, outputFile: File, onProgress: (Int) -> Unit): AudioProjectData? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var outputStream: BufferedOutputStream? = null
        
        try {
            extractor.setDataSource(inputFile.absolutePath)
            var format: MediaFormat? = null
            var trackIdx = -1
            
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    format = f; trackIdx = i; break
                }
            }
            if (format == null || trackIdx == -1) return null

            extractor.selectTrack(trackIdx)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val durationUs = format.getLong(MediaFormat.KEY_DURATION)
            
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            outputStream = BufferedOutputStream(FileOutputStream(outputFile))
            val peaksList = ArrayList<Float>()
            
            val info = MediaCodec.BufferInfo()
            var isInputEOS = false
            var isOutputEOS = false
            
            var outputSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            
            // Pour le calcul des pics
            var samplesPerPoint = outputSampleRate / POINTS_PER_SECOND
            var peakAccumulator = 0f
            var sampleCounter = 0
            
            // Progression
            var processedUs = 0L

            while (!isOutputEOS) {
                if (!isInputEOS) {
                    val inIdx = codec.dequeueInputBuffer(5000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)
                        val sz = extractor.readSampleData(buf!!, 0)
                        if (sz < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isInputEOS = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, 5000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outputSampleRate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channelCount = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    samplesPerPoint = outputSampleRate / POINTS_PER_SECOND
                } else if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)
                    if (outBuf != null && info.size > 0) {
                        outBuf.order(ByteOrder.LITTLE_ENDIAN)
                        val shorts = outBuf.asShortBuffer()
                        
                        // Buffer pour écrire sur le disque
                        val chunkBytes = ByteArray(shorts.remaining() * 2 / channelCount) 
                        val chunkBuffer = ByteBuffer.wrap(chunkBytes).order(ByteOrder.LITTLE_ENDIAN)

                        while (shorts.hasRemaining()) {
                            // Mixage Down to Mono
                            var sum = 0
                            for (c in 0 until channelCount) {
                                if (shorts.hasRemaining()) sum += shorts.get()
                            }
                            val monoSample = (sum / channelCount).toShort()
                            
                            // 1. Écriture Disque
                            chunkBuffer.putShort(monoSample)
                            
                            // 2. Calcul Pics
                            val absVal = abs(monoSample.toFloat() / 32768f)
                            if (absVal > peakAccumulator) peakAccumulator = absVal
                            sampleCounter++
                            
                            if (sampleCounter >= samplesPerPoint) {
                                peaksList.add(peakAccumulator)
                                peakAccumulator = 0f
                                sampleCounter = 0
                            }
                        }
                        outputStream.write(chunkBytes)
                        
                        // Progression UI
                        processedUs += info.presentationTimeUs
                        val progress = ((info.presentationTimeUs.toDouble() / durationUs.toDouble()) * 100).toInt()
                        onProgress(progress)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) isOutputEOS = true
                }
            }

            outputStream.flush()
            outputStream.close()
            codec.stop()
            codec.release()
            extractor.release()

            // Sauvegarde des pics pour la prochaine fois
            val finalPeaks = peaksList.toFloatArray()
            savePeaksCache(inputFile, finalPeaks)

            return AudioProjectData(outputFile, finalPeaks, outputSampleRate, durationUs / 1000)

        } catch (e: Exception) {
            e.printStackTrace()
            outputStream?.close()
            return null
        }
    }

    private fun savePeaksCache(audioFile: File, peaks: FloatArray) {
        val cacheFile = File(audioFile.parent, "waveform.peaks")
        val bb = ByteBuffer.allocate(peaks.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        peaks.forEach { bb.putFloat(it) }
        cacheFile.writeBytes(bb.array())
    }
    
    // --- EXPORT FINAL (Raw -> M4A) ---
    fun exportRawToM4A(rawFile: File, destFile: File, sampleRate: Int): Boolean {
        if (!rawFile.exists()) return false
        var fis: FileInputStream? = null
        var muxer: MediaMuxer? = null
        var encoder: MediaCodec? = null
        
        try {
            fis = FileInputStream(rawFile)
            val buffer = ByteArray(4096) // Buffer de lecture
            
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1) // Mono export
            format.setInteger(MediaFormat.KEY_BIT_RATE, 128000)
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            
            muxer = MediaMuxer(destFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var trackIndex = -1
            var muxerStarted = false
            val info = MediaCodec.BufferInfo()
            
            var isEOS = false
            
            while (true) {
                if (!isEOS) {
                    val inIdx = encoder.dequeueInputBuffer(10000)
                    if (inIdx >= 0) {
                        val read = fis.read(buffer)
                        if (read == -1) {
                            encoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isEOS = true
                        } else {
                            val inputBuf = encoder.getInputBuffer(inIdx)
                            inputBuf?.clear()
                            inputBuf?.put(buffer, 0, read)
                            encoder.queueInputBuffer(inIdx, 0, read, System.nanoTime() / 1000, 0)
                        }
                    }
                }
                
                val outIdx = encoder.dequeueOutputBuffer(info, 10000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    trackIndex = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    muxerStarted = true
                } else if (outIdx >= 0) {
                    val encodedData = encoder.getOutputBuffer(outIdx)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size != 0 && muxerStarted) {
                        encodedData?.position(info.offset)
                        encodedData?.limit(info.offset + info.size)
                        muxer.writeSampleData(trackIndex, encodedData!!, info)
                    }
                    encoder.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            return true
        } catch(e: Exception) { return false }
        finally {
            try { fis?.close() } catch(e:Exception){}
            try { encoder?.stop(); encoder?.release() } catch(e:Exception){}
            try { muxer?.stop(); muxer?.release() } catch(e:Exception){}
        }
    }

    // --- COUPE RAPIDE (Disque à Disque) ---
    fun cutRawFile(rawFile: File, sampleRate: Int, startPoint: Int, endPoint: Int): Boolean {
        // startPoint / endPoint sont des index de l'onde (1 point = 20ms)
        // 1 sample (Short) = 2 bytes
        val bytesPerSec = sampleRate * 2
        val bytesPerPoint = bytesPerSec / POINTS_PER_SECOND
        
        val startByte = startPoint.toLong() * bytesPerPoint
        val endByte = endPoint.toLong() * bytesPerPoint
        
        val tempFile = File(rawFile.parent, "temp_cut.raw")
        
        try {
            val raf = RandomAccessFile(rawFile, "r")
            val fos = FileOutputStream(tempFile)
            val buffer = ByteArray(8192)
            
            // 1. Copier avant la coupe
            raf.seek(0)
            var remaining = startByte
            while (remaining > 0) {
                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                val read = raf.read(buffer, 0, toRead)
                if (read == -1) break
                fos.write(buffer, 0, read)
                remaining -= read
            }
            
            // 2. Sauter la coupe
            raf.seek(endByte)
            
            // 3. Copier le reste
            var read: Int
            while (raf.read(buffer).also { read = it } != -1) {
                fos.write(buffer, 0, read)
            }
            
            raf.close()
            fos.close()
            
            // Remplacer
            rawFile.delete()
            tempFile.renameTo(rawFile)
            return true
        } catch(e: Exception) { return false }
    }
}