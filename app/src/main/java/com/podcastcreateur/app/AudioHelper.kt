package com.podcastcreateur.app

import android.media.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

data class AudioMetadata(
    val sampleRate: Int,
    val channelCount: Int,
    val duration: Long, 
    val totalSamples: Long
)

object AudioHelper {
    private const val BIT_RATE = 128000
    // Utilisé pour certaines estimations, mais moins critique maintenant qu'on a Amplituda
    const val POINTS_PER_SECOND = 50 

    fun getAudioMetadata(input: File): AudioMetadata? {
        if (!input.exists()) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(input.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
            val sampleRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toInt() ?: 44100
            // On suppose 1 canal si info manquante pour simplifier, ou on utilise MediaExtractor
            val totalSamples = (durationMs * sampleRate) / 1000
            AudioMetadata(sampleRate, 1, durationMs, totalSamples)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            retriever.release()
        }
    }

    /**
     * COUPE (STREAMING) : Ne charge pas tout en RAM.
     * Lit le fichier source et écrit dans le fichier destination en sautant la partie coupée.
     */
    fun deleteRegionStreaming(input: File, output: File, startSample: Int, endSample: Int): Boolean {
        // Cette fonction reste similaire à votre version précédente qui était déjà en streaming,
        // c'est la seule qui était correcte pour les gros fichiers.
        return runTranscode(input, output) { sampleIndex, sampleValue ->
            if (sampleIndex in startSample until endSample) {
                null // On retourne null pour dire "ne pas écrire ce sample" (coupure)
            } else {
                sampleValue // On garde le sample
            }
        }
    }

    /**
     * NORMALISATION (STREAMING) : 2 Passes.
     * 1. On scanne pour trouver le Max.
     * 2. On réécrit en multipliant par le gain.
     */
    fun normalizeAudio(inputFile: File, outputFile: File, startMs: Long, endMs: Long, sampleRate: Int, targetPeak: Float, onProgress: (Float)->Unit): Boolean {
        // PASSE 1 : Trouver le pic maximum actuel
        var maxPeakFound = 0f
        
        // On utilise une version simplifiée de lecture pour aller vite
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(inputFile.absolutePath)
            val trackIdx = selectAudioTrack(extractor)
            if (trackIdx < 0) return false
            extractor.selectTrack(trackIdx)
            val format = extractor.getTrackFormat(trackIdx)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return false
            val decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()

            val bufferInfo = MediaCodec.BufferInfo()
            var isEOS = false
            
            while (!isEOS) {
                val inIdx = decoder.dequeueInputBuffer(1000)
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
                
                var outIdx = decoder.dequeueOutputBuffer(bufferInfo, 1000)
                while (outIdx >= 0) {
                    val outBuf = decoder.getOutputBuffer(outIdx)
                    if (outBuf != null && bufferInfo.size > 0) {
                        val shorts = outBuf.asShortBuffer()
                        while (shorts.hasRemaining()) {
                            val sample = abs(shorts.get().toFloat() / 32768f)
                            if (sample > maxPeakFound) maxPeakFound = sample
                        }
                    }
                    decoder.releaseOutputBuffer(outIdx, false)
                    outIdx = decoder.dequeueOutputBuffer(bufferInfo, 1000)
                }
            }
            decoder.stop(); decoder.release()
        } catch (e: Exception) { return false } 
        finally { extractor.release() }

        if (maxPeakFound < 0.01f) return false // Fichier silencieux
        
        val gain = targetPeak / maxPeakFound
        if (gain <= 1.0f && gain >= 0.99f) return true // Pas besoin de normaliser

        onProgress(0.5f)

        // PASSE 2 : Appliquer le gain via Transcodage Streaming
        return runTranscode(inputFile, outputFile) { _, sampleValue ->
            // On applique le gain et on clamp pour éviter la saturation numérique
            val newVal = (sampleValue * gain).toInt().coerceIn(-32768, 32767)
            newVal.toShort()
        }
    }

    /**
     * FUSION (STREAMING) : Concatène plusieurs fichiers audio sans saturer la RAM.
     */
    fun mergeFiles(inputs: List<File>, output: File): Boolean {
        if (inputs.isEmpty()) return false
        
        // Configuration de l'encodeur (AAC)
        val sampleRate = 44100
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1)
        format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerTrackIndex = -1
        var muxerStarted = false
        
        try {
            // Pour chaque fichier d'entrée
            for (input in inputs) {
                if (!input.exists()) continue
                
                val extractor = MediaExtractor()
                extractor.setDataSource(input.absolutePath)
                val trackIdx = selectAudioTrack(extractor)
                if (trackIdx < 0) { extractor.release(); continue }
                
                extractor.selectTrack(trackIdx)
                val decFormat = extractor.getTrackFormat(trackIdx)
                val decoder = MediaCodec.createDecoderByType(decFormat.getString(MediaFormat.KEY_MIME)!!)
                decoder.configure(decFormat, null, null, 0)
                decoder.start()

                val bufferInfo = MediaCodec.BufferInfo()
                var isEOS = false
                
                while (!isEOS) {
                    // 1. Lire / Décoder
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

                    // 2. Récupérer PCM décodé -> Envoyer à l'Encodeur
                    var outIdx = decoder.dequeueOutputBuffer(bufferInfo, 2000)
                    while (outIdx >= 0) {
                        val outBuf = decoder.getOutputBuffer(outIdx)
                        if (outBuf != null && bufferInfo.size > 0) {
                            val chunk = ByteArray(bufferInfo.size)
                            outBuf.position(bufferInfo.offset)
                            outBuf.get(chunk)
                            // On envoie direct à l'encodeur global
                            feedEncoder(encoder, chunk)
                        }
                        decoder.releaseOutputBuffer(outIdx, false)
                        outIdx = decoder.dequeueOutputBuffer(bufferInfo, 2000)
                    }
                    
                    // 3. Gérer la sortie de l'Encodeur vers le Muxer
                    drainEncoder(encoder, muxer) { idx, started -> 
                        muxerTrackIndex = idx
                        muxerStarted = started 
                    }
                }
                decoder.stop(); decoder.release(); extractor.release()
            }
            
            // Fin du flux global
            val inIdx = encoder.dequeueInputBuffer(2000)
            if (inIdx >= 0) encoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drainEncoder(encoder, muxer) { _, _ -> }
            
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        } finally {
            try { encoder.stop(); encoder.release() } catch (e:Exception){}
            try { if (muxerStarted) muxer.stop(); muxer.release() } catch (e:Exception){}
        }
    }

    // --- FONCTIONS UTILITAIRES PRIVÉES ---

    /**
     * Structure générique pour lire un fichier, modifier les samples un par un, et réécrire.
     * Le callback `process` retourne le nouveau sample, ou null pour le supprimer.
     */
    private fun runTranscode(input: File, output: File, process: (Long, Short) -> Short?): Boolean {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(input.absolutePath)
            val trackIdx = selectAudioTrack(extractor)
            if (trackIdx < 0) return false
            extractor.selectTrack(trackIdx)
            val format = extractor.getTrackFormat(trackIdx)
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            
            decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            decoder.configure(format, null, null, 0)
            decoder.start()

            val encFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1)
            encFormat.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            encFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            encFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            
            val bufferInfo = MediaCodec.BufferInfo()
            var muxerTrackIndex = -1
            var muxerStarted = false
            var totalSamplesProcessed = 0L
            var isInputEOS = false
            var isDecodedEOS = false
            
            while (!isDecodedEOS) {
                if (!isInputEOS) {
                    val inIdx = decoder.dequeueInputBuffer(1000)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx)
                        val sz = extractor.readSampleData(buf!!, 0)
                        if (sz < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isInputEOS = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                var outIdx = decoder.dequeueOutputBuffer(bufferInfo, 1000)
                while (outIdx >= 0) {
                    val outBuf = decoder.getOutputBuffer(outIdx)
                    if (outBuf != null && bufferInfo.size > 0) {
                        // Traitement PCM
                        val chunkBytes = ByteArray(bufferInfo.size)
                        outBuf.position(bufferInfo.offset)
                        outBuf.get(chunkBytes)
                        
                        // Conversion Bytes -> Shorts
                        val shortBuffer = ByteBuffer.wrap(chunkBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val processedStream = java.io.ByteArrayOutputStream()
                        val tempShortBuf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)

                        while (shortBuffer.hasRemaining()) {
                            val sample = shortBuffer.get()
                            // APPEL AU CALLBACK DE MODIFICATION
                            val newSample = process(totalSamplesProcessed, sample)
                            totalSamplesProcessed++
                            
                            if (newSample != null) {
                                tempShortBuf.clear()
                                tempShortBuf.putShort(newSample)
                                processedStream.write(tempShortBuf.array())
                            }
                        }
                        
                        val bytesToWrite = processedStream.toByteArray()
                        if (bytesToWrite.isNotEmpty()) {
                            feedEncoder(encoder, bytesToWrite)
                        }
                    }
                    decoder.releaseOutputBuffer(outIdx, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        isDecodedEOS = true
                        val inIdx = encoder.dequeueInputBuffer(1000)
                        if (inIdx >= 0) encoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    }
                    outIdx = decoder.dequeueOutputBuffer(bufferInfo, 1000)
                }
                
                // Muxing
                drainEncoder(encoder, muxer) { idx, started ->
                    muxerTrackIndex = idx
                    muxerStarted = started
                }
            }
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        } finally {
            try { decoder?.stop(); decoder?.release() } catch(e:Exception){}
            try { encoder?.stop(); encoder?.release() } catch(e:Exception){}
            try { if (muxer != null && muxerStarted) muxer.stop(); muxer?.release() } catch(e:Exception){}
            try { extractor?.release() } catch(e:Exception){}
        }
    }

    private fun feedEncoder(encoder: MediaCodec, data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            val inIdx = encoder.dequeueInputBuffer(2000)
            if (inIdx >= 0) {
                val buf = encoder.getInputBuffer(inIdx)
                val remaining = data.size - offset
                val toWrite = if (remaining > buf!!.capacity()) buf.capacity() else remaining
                buf.clear()
                buf.put(data, offset, toWrite)
                encoder.queueInputBuffer(inIdx, 0, toWrite, System.nanoTime()/1000, 0)
                offset += toWrite
            }
        }
    }

    // Fonction Helper pour vider l'encodeur vers le Muxer
    private var muxerStartedGlobal = false // Attention : pour l'exemple simplifié. En prod, passer en paramètre.
    private fun drainEncoder(encoder: MediaCodec, muxer: MediaMuxer, onStart: (Int, Boolean)->Unit) {
        val info = MediaCodec.BufferInfo()
        var outIdx = encoder.dequeueOutputBuffer(info, 1000)
        
        while (outIdx >= 0 || outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val trackIndex = muxer.addTrack(encoder.outputFormat)
                muxer.start()
                onStart(trackIndex, true)
                muxerStartedGlobal = true 
            } else if (outIdx >= 0) {
                val encodedData = encoder.getOutputBuffer(outIdx)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                
                if (info.size != 0 && muxerStartedGlobal) { // on vérifie un flag local ou via callback
                    encodedData?.position(info.offset)
                    encodedData?.limit(info.offset + info.size)
                    // Note: Dans une archi propre, on doit stocker l'index de track retourné par addTrack
                    // Ici on suppose qu'il n'y a qu'une track (0) pour l'audio mono
                    muxer.writeSampleData(0, encodedData!!, info) 
                }
                encoder.releaseOutputBuffer(outIdx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
            outIdx = encoder.dequeueOutputBuffer(info, 1000)
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