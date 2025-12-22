package com.podcastcreateur.app

import android.media.*
import android.util.Log
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

// Structure de données pour le mode "Disque"
data class AudioProjectData(
    val rawFile: File,     // Le fichier audio brut (PCM 16bit Mono)
    val peaks: FloatArray, // Les données visuelles (Cache RAM léger)
    val sampleRate: Int,
    val durationMs: Long
)

object AudioHelper {
    const val POINTS_PER_SECOND = 50 // Résolution de l'onde (1 point = 20ms)

    /**
     * INITIALISATION DU PROJET (MP3 -> RAW + PEAKS)
     * Cette fonction prépare le terrain pour une édition fluide.
     */
    fun prepareProject(inputFile: File, projectDir: File, onProgress: (Int) -> Unit): AudioProjectData? {
        val rawFile = File(projectDir, "working_audio.raw")
        val cachePeaks = File(projectDir, "waveform.peaks")
        
        // Si le cache existe déjà, ouverture INSTANTANÉE
        if (rawFile.exists() && rawFile.length() > 0 && cachePeaks.exists()) {
            return try {
                val bytes = cachePeaks.readBytes()
                val floats = FloatArray(bytes.size / 4)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
                // On estime le sampleRate (standard 44100) et la durée via la taille du fichier
                AudioProjectData(rawFile, floats, 44100, (rawFile.length() / 2 / 44.1).toLong())
            } catch (e: Exception) {
                // Si cache corrompu, on refait
                convertFileToRaw(inputFile, rawFile, onProgress)
            }
        }

        return convertFileToRaw(inputFile, rawFile, onProgress)
    }

    /**
     * CONVERSION DISQUE À DISQUE (Pas de saturation RAM)
     */
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
            var channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
            
            var samplesPerPoint = outputSampleRate / POINTS_PER_SECOND
            var peakAccumulator = 0f
            var sampleCounter = 0
            
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
                        
                        // Buffer pour écriture disque (Mono)
                        val chunkBytes = ByteArray(shorts.remaining() * 2 / channelCount) 
                        val chunkBuffer = ByteBuffer.wrap(chunkBytes).order(ByteOrder.LITTLE_ENDIAN)

                        while (shorts.hasRemaining()) {
                            // Mixage Down to Mono (Indispensable pour fichiers longs)
                            var sum = 0
                            for (c in 0 until channelCount) {
                                if (shorts.hasRemaining()) sum += shorts.get()
                            }
                            val monoSample = (sum / channelCount).toShort()
                            
                            // 1. Écriture
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
                        
                        // Progression
                        if (durationUs > 0) {
                            val progress = ((info.presentationTimeUs.toDouble() / durationUs.toDouble()) * 100).toInt()
                            onProgress(progress.coerceIn(0, 100))
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) isOutputEOS = true
                }
            }

            outputStream.flush(); outputStream.close()
            codec.stop(); codec.release(); extractor.release()

            val finalPeaks = peaksList.toFloatArray()
            savePeaksCache(inputFile, finalPeaks)

            return AudioProjectData(outputFile, finalPeaks, outputSampleRate, durationUs / 1000)

        } catch (e: Exception) {
            e.printStackTrace()
            try { outputStream?.close() } catch(ex:Exception){}
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
            val buffer = ByteArray(4096)
            
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1)
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

    // --- COUPE RAPIDE (DISQUE) ---
    fun cutRawFile(rawFile: File, sampleRate: Int, startPoint: Int, endPoint: Int): Boolean {
        val bytesPerSec = sampleRate * 2
        val bytesPerPoint = bytesPerSec / POINTS_PER_SECOND
        val startByte = startPoint.toLong() * bytesPerPoint
        val endByte = endPoint.toLong() * bytesPerPoint
        
        val tempFile = File(rawFile.parent, "temp_cut.raw")
        try {
            val raf = RandomAccessFile(rawFile, "r")
            val fos = FileOutputStream(tempFile)
            val buffer = ByteArray(8192)
            
            // 1. Copier début
            raf.seek(0)
            var remaining = startByte
            while (remaining > 0) {
                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                val read = raf.read(buffer, 0, toRead)
                if (read == -1) break
                fos.write(buffer, 0, read)
                remaining -= read
            }
            // 2. Sauter sélection
            raf.seek(endByte)
            // 3. Copier fin
            var read: Int
            while (raf.read(buffer).also { read = it } != -1) {
                fos.write(buffer, 0, read)
            }
            raf.close(); fos.close()
            rawFile.delete(); tempFile.renameTo(rawFile)
            return true
        } catch(e: Exception) { return false }
    }

    // --- FUSION DE FICHIERS (FIX POUR PROJECT ACTIVITY) ---
    fun mergeFiles(inputs: List<File>, output: File): Boolean {
        if (inputs.isEmpty()) return false
        val tempRaw = File(output.parent, "temp_merge_global.raw")
        
        try {
            val fos = FileOutputStream(tempRaw)
            val bos = BufferedOutputStream(fos)
            var sampleRate = 44100 // Par défaut

            // 1. On décode tout vers un gros fichier RAW temporaire
            for (file in inputs) {
                if (!file.exists()) continue
                // On utilise une version simplifiée de convertToRaw qui append au stream
                val extractor = MediaExtractor()
                extractor.setDataSource(file.absolutePath)
                var format: MediaFormat? = null
                var trackIdx = -1
                for (i in 0 until extractor.trackCount) {
                    val f = extractor.getTrackFormat(i)
                    if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                        format = f; trackIdx = i; break
                    }
                }
                if (format == null) continue
                extractor.selectTrack(trackIdx)
                val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
                codec.configure(format, null, null, 0)
                codec.start()
                
                sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
                
                val info = MediaCodec.BufferInfo()
                var eos = false
                while (!eos) {
                    val inIdx = codec.dequeueInputBuffer(5000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)
                        val sz = extractor.readSampleData(buf!!, 0)
                        if (sz < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eos = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                    val outIdx = codec.dequeueOutputBuffer(info, 5000)
                    if (outIdx >= 0) {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0) {
                            outBuf.order(ByteOrder.LITTLE_ENDIAN)
                            val shorts = outBuf.asShortBuffer()
                            val chunk = ByteArray(shorts.remaining() * 2 / channels)
                            val chunkBuff = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
                            while(shorts.hasRemaining()) {
                                var sum = 0
                                for(c in 0 until channels) if(shorts.hasRemaining()) sum += shorts.get()
                                chunkBuff.putShort((sum/channels).toShort())
                            }
                            bos.write(chunk)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
                    }
                }
                codec.stop(); codec.release(); extractor.release()
            }
            bos.flush(); bos.close()

            // 2. On encode le RAW global vers le M4A final
            val success = exportRawToM4A(tempRaw, output, sampleRate)
            tempRaw.delete()
            return success

        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }
}