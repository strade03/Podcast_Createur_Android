package com.podcastcreateur.app

import android.media.*
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class AudioContent(
    val data: ShortArray,
    val sampleRate: Int
)

data class AudioMetadata(
    val sampleRate: Int,
    val totalSamples: Long,
    val channelCount: Int,
    val durationSeconds: Long
)

object AudioHelper {
    private const val BIT_RATE = 128000
    private const val WAVEFORM_SAMPLES = 10000 // Nombre de points pour la waveform

    /**
     * NOUVELLE APPROCHE : Ne charge que les métadonnées et un aperçu
     */
    fun getAudioMetadata(input: File): AudioMetadata? {
        if (!input.exists()) return null
        
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(input.absolutePath)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }

        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio/") == true) {
                format = f
                break
            }
        }

        if (format == null) {
            extractor.release()
            return null
        }

        val sampleRate = try {
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } catch (e: Exception) {
            44100
        }

        val durationUs = try {
            format.getLong(MediaFormat.KEY_DURATION)
        } catch (e: Exception) {
            0L
        }

        val channelCount = try {
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        } catch (e: Exception) {
            1
        }

        val totalSamples = if (durationUs > 0) {
            ((durationUs / 1_000_000.0) * sampleRate).toLong()
        } else {
            0L
        }

        val durationSeconds = durationUs / 1_000_000

        extractor.release()

        return AudioMetadata(sampleRate, totalSamples, channelCount, durationSeconds)
    }

    /**
     * Charge uniquement un aperçu pour l'affichage de la waveform
     * On prend 1 sample tous les N samples pour avoir ~10000 points
     */
    fun loadWaveformPreview(input: File, targetPoints: Int = WAVEFORM_SAMPLES): AudioContent {
        val metadata = getAudioMetadata(input) ?: return AudioContent(ShortArray(0), 44100)
        
        if (metadata.totalSamples == 0L) {
            return AudioContent(ShortArray(0), metadata.sampleRate)
        }

        // Calculer le facteur de sous-échantillonnage
        val skipFactor = (metadata.totalSamples / targetPoints).toInt().coerceAtLeast(1)
        
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(input.absolutePath)
        } catch (e: Exception) {
            return AudioContent(ShortArray(0), metadata.sampleRate)
        }

        var trackIndex = -1
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio/") == true) {
                trackIndex = i
                break
            }
        }

        if (trackIndex < 0) {
            extractor.release()
            return AudioContent(ShortArray(0), metadata.sampleRate)
        }

        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return AudioContent(ShortArray(0), metadata.sampleRate)
        
        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(format, null, null, 0)
        decoder.start()

        val bufferInfo = MediaCodec.BufferInfo()
        val previewData = ArrayList<Short>()
        var sampleCounter = 0
        var actualChannels = metadata.channelCount

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
                    actualChannels = try {
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
                        
                        // Convertir en shorts et sous-échantillonner
                        val shorts = ShortArray(chunk.size / 2)
                        ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                        
                        // Prendre 1 sample tous les skipFactor
                        for (i in shorts.indices step (skipFactor * actualChannels)) {
                            if (actualChannels == 2 && i + 1 < shorts.size) {
                                // Stéréo -> Mono
                                val mono = ((shorts[i].toInt() + shorts[i + 1].toInt()) / 2).toShort()
                                previewData.add(mono)
                            } else if (i < shorts.size) {
                                previewData.add(shorts[i])
                            }
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
            try { decoder.stop(); decoder.release() } catch(e: Exception) {}
            try { extractor.release() } catch(e: Exception) {}
        }

        val result = ShortArray(previewData.size)
        for (i in previewData.indices) result[i] = previewData[i]
        
        return AudioContent(result, metadata.sampleRate)
    }

    /**
     * Fusion STREAMING : Pas de chargement en RAM
     * On décode chunk par chunk et on écrit directement
     */
    fun mergeFilesStreaming(inputs: List<File>, output: File, onProgress: ((Int) -> Unit)? = null): Boolean {
        if (inputs.isEmpty()) return false

        // 1. Déterminer le sample rate maître
        val firstMetadata = getAudioMetadata(inputs[0]) ?: return false
        val masterSampleRate = firstMetadata.sampleRate

        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null

        try {
            // 2. Configurer l'encodeur
            val mime = MediaFormat.MIMETYPE_AUDIO_AAC
            val format = MediaFormat.createAudioFormat(mime, masterSampleRate, 1)
            format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)

            encoder = MediaCodec.createEncoderByType(mime)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var audioTrackIndex = -1
            var muxerStarted = false
            val outputBufferInfo = MediaCodec.BufferInfo()
            var totalPts = 0L

            // 3. Traiter chaque fichier un par un
            inputs.forEachIndexed { fileIndex, inputFile ->
                onProgress?.invoke((fileIndex * 100) / inputs.size)
                
                val extractor = MediaExtractor()
                extractor.setDataSource(inputFile.absolutePath)
                
                var trackIndex = -1
                var inputFormat: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val f = extractor.getTrackFormat(i)
                    val m = f.getString(MediaFormat.KEY_MIME)
                    if (m?.startsWith("audio/") == true) {
                        trackIndex = i
                        inputFormat = f
                        break
                    }
                }
                
                if (trackIndex < 0 || inputFormat == null) {
                    extractor.release()
                    continue
                }

                extractor.selectTrack(trackIndex)
                val inputMime = inputFormat.getString(MediaFormat.KEY_MIME) ?: continue
                val decoder = MediaCodec.createDecoderByType(inputMime)
                decoder.configure(inputFormat, null, null, 0)
                decoder.start()

                val bufferInfo = MediaCodec.BufferInfo()
                var inputChannels = 1

                try {
                    var isEOS = false
                    while (true) {
                        // Décoder
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
                            inputChannels = try {
                                outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            } catch (e: Exception) { 1 }
                        }
                        
                        if (outIndex >= 0) {
                            val decodedData = decoder.getOutputBuffer(outIndex)
                            if (decodedData != null && bufferInfo.size > 0) {
                                // Convertir stéréo -> mono si nécessaire
                                val pcmBytes = ByteArray(bufferInfo.size)
                                decodedData.get(pcmBytes)
                                decodedData.clear()
                                
                                val pcmShorts = ShortArray(pcmBytes.size / 2)
                                ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcmShorts)
                                
                                val monoShorts = if (inputChannels == 2) {
                                    val mono = ShortArray(pcmShorts.size / 2)
                                    for (i in mono.indices) {
                                        mono[i] = ((pcmShorts[i * 2].toInt() + pcmShorts[i * 2 + 1].toInt()) / 2).toShort()
                                    }
                                    mono
                                } else {
                                    pcmShorts
                                }

                                // Encoder directement
                                val monoBytes = ByteArray(monoShorts.size * 2)
                                ByteBuffer.wrap(monoBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(monoShorts)
                                
                                var offset = 0
                                while (offset < monoBytes.size) {
                                    val encIndex = encoder.dequeueInputBuffer(10000)
                                    if (encIndex >= 0) {
                                        val encBuffer = encoder.getInputBuffer(encIndex)
                                        encBuffer?.clear()
                                        val toWrite = minOf(4096, monoBytes.size - offset)
                                        encBuffer?.put(monoBytes, offset, toWrite)
                                        
                                        encoder.queueInputBuffer(encIndex, 0, toWrite, totalPts, 0)
                                        totalPts += (toWrite * 1000000L) / (masterSampleRate * 2)
                                        offset += toWrite
                                    }
                                    
                                    // Lire l'output de l'encodeur
                                    val (newTrack, newStarted) = drainEncoder(encoder, muxer, outputBufferInfo, audioTrackIndex, muxerStarted) { track, started ->
                                        audioTrackIndex = track
                                        muxerStarted = started
                                    }
                                    audioTrackIndex = newTrack
                                    muxerStarted = newStarted
                                }
                            }
                            decoder.releaseOutputBuffer(outIndex, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                        } else if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                            if (isEOS) break
                        }
                    }
                } finally {
                    try { decoder.stop(); decoder.release() } catch(e: Exception) {}
                    try { extractor.release() } catch(e: Exception) {}
                }
            }

            // 4. Finaliser l'encodeur
            val encIndex = encoder.dequeueInputBuffer(10000)
            if (encIndex >= 0) {
                encoder.queueInputBuffer(encIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            
            while (true) {
                val outIndex = encoder.dequeueOutputBuffer(outputBufferInfo, 10000)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = encoder.outputFormat
                    audioTrackIndex = muxer.addTrack(newFormat)
                    muxer.start()
                    muxerStarted = true
                } else if (outIndex >= 0) {
                    val encodedData = encoder.getOutputBuffer(outIndex)
                    if ((outputBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        outputBufferInfo.size = 0
                    }
                    if (outputBufferInfo.size != 0 && muxerStarted) {
                        encodedData?.position(outputBufferInfo.offset)
                        encodedData?.limit(outputBufferInfo.offset + outputBufferInfo.size)
                        muxer.writeSampleData(audioTrackIndex, encodedData!!, outputBufferInfo)
                    }
                    encoder.releaseOutputBuffer(outIndex, false)
                    if ((outputBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                }
            }

            onProgress?.invoke(100)
            return true
            
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        } finally {
            try { encoder?.stop(); encoder?.release() } catch(e: Exception) {}
            try { muxer?.stop(); muxer?.release() } catch(e: Exception) {}
        }
    }

    private fun drainEncoder(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        bufferInfo: MediaCodec.BufferInfo,
        trackIndex: Int,
        started: Boolean,
        onTrackAdded: (Int, Boolean) -> Unit
    ): Pair<Int, Boolean> {
        var currentTrack = trackIndex
        var currentStarted = started
        
        var shouldContinue = true
        while (shouldContinue) {
            val outIndex = encoder.dequeueOutputBuffer(bufferInfo, 0)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!currentStarted) {
                        val format = encoder.outputFormat
                        currentTrack = muxer.addTrack(format)
                        muxer.start()
                        currentStarted = true
                        onTrackAdded(currentTrack, currentStarted)
                    }
                }
                outIndex >= 0 -> {
                    val encodedData = encoder.getOutputBuffer(outIndex)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        bufferInfo.size = 0
                    }
                    if (bufferInfo.size != 0 && currentStarted) {
                        encodedData?.position(bufferInfo.offset)
                        encodedData?.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(currentTrack, encodedData!!, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(outIndex, false)
                }
                else -> {
                    shouldContinue = false
                }
            }
        }
        return Pair(currentTrack, currentStarted)
    }

    // Ancienne fonction gardée pour compatibilité mais non recommandée
    @Deprecated("Use loadWaveformPreview instead")
    fun decodeToPCM(input: File, maxSamples: Int = Int.MAX_VALUE): AudioContent {
        return loadWaveformPreview(input)
    }

    fun savePCMToAAC(pcmData: ShortArray, outputFile: File, sampleRate: Int): Boolean {
        // Fonction conservée pour l'édition de petits fichiers
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
            try { encoder?.stop(); encoder?.release() } catch(e: Exception) {}
            try { muxer?.stop(); muxer?.release() } catch(e: Exception) {}
        }
    }

    @Deprecated("Use mergeFilesStreaming instead")
    fun mergeFiles(inputs: List<File>, output: File): Boolean {
        return mergeFilesStreaming(inputs, output)
    }
}