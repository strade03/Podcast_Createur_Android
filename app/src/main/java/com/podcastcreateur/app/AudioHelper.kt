package com.podcastcreateur.app

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

data class AudioContent(
    val data: ShortArray,
    val sampleRate: Int
)

data class AudioMetadata(
    val sampleRate: Int,
    val totalSamples: Long,
    val channelCount: Int,
    val durationUs: Long // CORRECTION : On utilise bien durationUs ici
)

object AudioHelper {
    // On cible environ 8000 points pour dessiner la courbe sur la largeur de l'écran
    private const val TARGET_WAVEFORM_POINTS = 8000 

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

        val sampleRate = try { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) } catch (e: Exception) { 44100 }
        
        // CORRECTION : Récupération précise en microsecondes
        val durationUs = try { format.getLong(MediaFormat.KEY_DURATION) } catch (e: Exception) { 0L }
        
        val channelCount = try { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) } catch (e: Exception) { 1 }
        
        // Calcul des samples totaux basé sur la durée précise
        val totalSamples = if (durationUs > 0) ((durationUs / 1_000_000.0) * sampleRate).toLong() else 0L

        extractor.release()
        return AudioMetadata(sampleRate, totalSamples, channelCount, durationUs)
    }

    /**
     * Charge une Waveform précise (Peak Detection).
     */
    fun loadWaveformPreview(input: File): AudioContent {
        val metadata = getAudioMetadata(input) ?: return AudioContent(ShortArray(0), 44100)
        if (metadata.totalSamples == 0L) return AudioContent(ShortArray(0), metadata.sampleRate)

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
        
        val totalExpectedSamples = metadata.totalSamples
        val samplesPerPoint = (totalExpectedSamples / TARGET_WAVEFORM_POINTS).toInt().coerceAtLeast(1)
        
        var currentMaxSample = 0
        var samplesAccumulated = 0

        try {
            var isEOS = false
            while (!isEOS) {
                val inIndex = decoder.dequeueInputBuffer(5000)
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

                var outIndex = decoder.dequeueOutputBuffer(bufferInfo, 5000)
                while (outIndex >= 0) {
                    val outBuffer = decoder.getOutputBuffer(outIndex)
                    if (outBuffer != null && bufferInfo.size > 0) {
                        val chunk = ByteArray(bufferInfo.size)
                        outBuffer.get(chunk)
                        outBuffer.clear()
                        
                        val shorts = ShortArray(chunk.size / 2)
                        ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                        
                        for (sample in shorts) {
                            val absSample = abs(sample.toInt())
                            if (absSample > currentMaxSample) {
                                currentMaxSample = absSample
                            }
                            samplesAccumulated++

                            if (samplesAccumulated >= samplesPerPoint) {
                                previewData.add(currentMaxSample.toShort())
                                currentMaxSample = 0
                                samplesAccumulated = 0
                            }
                        }
                    }
                    decoder.releaseOutputBuffer(outIndex, false)
                    outIndex = decoder.dequeueOutputBuffer(bufferInfo, 0)
                }
                
                if (previewData.size > TARGET_WAVEFORM_POINTS * 2) break
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
}