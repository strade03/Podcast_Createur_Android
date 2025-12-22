package com.podcastcreateur.app

import android.media.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import android.media.MediaMetadataRetriever

data class AudioMetadata(
    val sampleRate: Int,
    val channelCount: Int,
    val duration: Long, 
    val totalSamples: Long
)

data class AudioContent(
    val data: ShortArray,
    val sampleRate: Int,
    val channelCount: Int 
)

object AudioHelper {
    private const val BIT_RATE = 128000
    const val POINTS_PER_SECOND = 50 

    private fun getDurationAccurate(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val time = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            time?.toLong() ?: 0L
        } catch (e: Exception) { 0L } 
        finally { retriever.release() }
    }

    fun getAudioMetadata(input: File): AudioMetadata? {
        if (!input.exists()) return null
        val accurateDuration = getDurationAccurate(input)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(input.absolutePath)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i; format = f; break
                }
            }
            if (trackIndex < 0 || format == null) return null
            
            val sampleRate = try { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) } catch (e: Exception) { 44100 }
            val channelCount = try { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) } catch (e: Exception) { 1 }
            val totalSamples = (accurateDuration * sampleRate) / 1000
            
            return AudioMetadata(sampleRate, channelCount, accurateDuration, totalSamples)
        } catch (e: Exception) {
            return null
        } finally { extractor.release() }
    }

    /**
     * Génération ultra-rapide de l'onde (Streaming)
     */
    fun loadWaveformStream(input: File, onUpdate: (FloatArray) -> Unit) {
        if (!input.exists()) { onUpdate(FloatArray(0)); return }
        
        // FIX : On récupère la durée pour calculer le 'step' d'optimisation
        val accurateDuration = getDurationAccurate(input)
        
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(input.absolutePath)
            var idx = -1
            var fmt: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { idx = i; fmt = f; break }
            }
            if (idx < 0 || fmt == null) return
            
            extractor.selectTrack(idx)
            val decoder = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            decoder.configure(fmt, null, null, 0)
            decoder.start()
            
            val info = MediaCodec.BufferInfo()
            val tempBuf = ArrayList<Float>()
            var maxPeak = 0f
            var count = 0
            var isEOS = false
            var currentSampleRate = 44100
            var currentChannels = 1
            var samplesPerPoint = currentSampleRate / POINTS_PER_SECOND
            
            // OPTIMISATION : Si le fichier est long (> 5 min), on ne lit qu'un échantillon sur 8
            val step = if (accurateDuration > 300_000) 8 else 1 

            while (true) {
                if (!isEOS) {
                    val inIdx = decoder.dequeueInputBuffer(5000)
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
                }
                
                val outIdx = decoder.dequeueOutputBuffer(info, 5000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    currentSampleRate = decoder.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    currentChannels = decoder.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    samplesPerPoint = (currentSampleRate * currentChannels) / POINTS_PER_SECOND
                }
                
                if (outIdx >= 0) {
                    val outBuf = decoder.getOutputBuffer(outIdx)
                    if (outBuf != null && info.size > 0) {
                        outBuf.order(ByteOrder.LITTLE_ENDIAN)
                        val shorts = outBuf.asShortBuffer()
                        
                        while (shorts.hasRemaining()) {
                            val sample = abs(shorts.get().toFloat() / 32768f)
                            if (sample > maxPeak) maxPeak = sample
                            count += step
                            
                            // Saut d'échantillons pour économiser le CPU sur les gros fichiers
                            if (step > 1 && shorts.hasRemaining()) {
                                val nextPos = (shorts.position() + step - 1).coerceAtMost(shorts.limit())
                                shorts.position(nextPos)
                            }

                            if (count >= samplesPerPoint) {
                                tempBuf.add(maxPeak.coerceAtMost(1.0f))
                                maxPeak = 0f
                                count = 0
                                if (tempBuf.size >= 200) {
                                    onUpdate(tempBuf.toFloatArray())
                                    tempBuf.clear()
                                }
                            }
                        }
                    }
                    decoder.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER && isEOS) break
            }
            if (tempBuf.isNotEmpty()) onUpdate(tempBuf.toFloatArray())
            decoder.stop(); decoder.release()
        } catch (e: Exception) { e.printStackTrace() } 
        finally { extractor.release() }
    }

    /**
     * Décode tout le fichier en mémoire vive (Mono-Mixing pour économiser la RAM)
     */
    fun decodeToPCM(input: File): AudioContent {
        val meta = getAudioMetadata(input) ?: return AudioContent(ShortArray(0), 44100, 1)
        val extractor = MediaExtractor()
        
        try {
            extractor.setDataSource(input.absolutePath)
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    format = f; extractor.selectTrack(i); break
                }
            }
            if (format == null) return AudioContent(ShortArray(0), 44100, 1)

            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()

            // PRÉ-ALLOCATION : On pré-alloue pour du MONO (gain de 50% de RAM sur fichiers stéréo)
            val totalExpectedSamples = ((meta.duration * meta.sampleRate) / 1000).toInt()
            val finalPcm = ShortArray(totalExpectedSamples)
            var writeOffset = 0

            val info = MediaCodec.BufferInfo()
            var isEOS = false
            val currentChannels = meta.channelCount

            while (true) {
                if (!isEOS) {
                    val inIdx = codec.dequeueInputBuffer(5000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)
                        val sz = extractor.readSampleData(buf!!, 0)
                        if (sz < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isEOS = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, 5000)
                if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)
                    if (outBuf != null && info.size > 0) {
                        outBuf.order(ByteOrder.LITTLE_ENDIAN)
                        val shorts = outBuf.asShortBuffer()
                        
                        while (shorts.hasRemaining()) {
                            if (currentChannels == 2) {
                                // MIXAGE STÉRÉO -> MONO (Moyenne)
                                val left = shorts.get().toInt()
                                val right = if (shorts.hasRemaining()) shorts.get().toInt() else left
                                val mono = ((left + right) / 2).toShort()
                                if (writeOffset < finalPcm.size) finalPcm[writeOffset++] = mono
                            } else {
                                if (writeOffset < finalPcm.size) finalPcm[writeOffset++] = shorts.get()
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                } else if (isEOS && outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) break
            }
            
            codec.stop(); codec.release(); extractor.release()
            return AudioContent(finalPcm.copyOfRange(0, writeOffset), meta.sampleRate, 1)
        } catch (e: Exception) { 
            return AudioContent(ShortArray(0), 44100, 1) 
        }
    }

    fun generateWaveformFromPCM(content: AudioContent): FloatArray {
        val samplesPerPoint = (content.sampleRate * content.channelCount) / POINTS_PER_SECOND
        if (samplesPerPoint <= 0) return floatArrayOf()
        
        val pointCount = content.data.size / samplesPerPoint
        val waveform = FloatArray(pointCount)
        
        for (i in 0 until pointCount) {
            var max = 0f
            val start = i * samplesPerPoint
            for (j in 0 until samplesPerPoint) {
                val idx = start + j
                if (idx >= content.data.size) break
                val sample = abs(content.data[idx].toFloat() / 32768f)
                if (sample > max) max = sample
            }
            waveform[i] = max.coerceAtMost(1.0f)
        }
        return waveform
    }

    fun savePCMToAAC(pcmData: ShortArray, outputFile: File, sampleRate: Int, channelCount: Int): Boolean {
        var enc: MediaCodec? = null
        var mux: MediaMuxer? = null
        try {
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount)
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            
            enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            
            mux = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var tr = -1; var st = false; val info = MediaCodec.BufferInfo()
            
            val bb = ByteBuffer.allocate(pcmData.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (s in pcmData) bb.putShort(s)
            val all = bb.array(); var off = 0; var eos = false
            
            while (true) {
                if (!eos) {
                    val i = enc.dequeueInputBuffer(1000)
                    if (i >= 0) {
                        val rem = all.size - off
                        val len = if (rem > 4096) 4096 else rem
                        if (len > 0) {
                            enc.getInputBuffer(i)?.put(all, off, len)
                            off += len
                            enc.queueInputBuffer(i, 0, len, System.nanoTime() / 1000, 0)
                        } else {
                            enc.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eos = true
                        }
                    }
                }
                val o = enc.dequeueOutputBuffer(info, 1000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    tr = mux.addTrack(enc.outputFormat); mux.start(); st = true
                } else if (o >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size != 0 && st) {
                        val d = enc.getOutputBuffer(o)
                        d?.position(info.offset); d?.limit(info.offset + info.size)
                        mux.writeSampleData(tr, d!!, info)
                    }
                    enc.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            return true
        } catch (e: Exception) { e.printStackTrace(); return false } 
        finally { try { enc?.stop(); enc?.release(); mux?.stop(); mux?.release() } catch (e: Exception) {} }
    }
    
    fun normalizeAudio(inputFile: File, outputFile: File, startMs: Long, endMs: Long, sampleRate: Int, targetPeak: Float, onProgress: (Float) -> Unit): Boolean {
        try {
            val content = decodeToPCM(inputFile)
            if (content.data.isEmpty()) return false
            onProgress(0.5f)
            var maxVal = 0f
            val startIdx = ((startMs * sampleRate * content.channelCount) / 1000).toInt()
            val endIdx = ((endMs * sampleRate * content.channelCount) / 1000).toInt().coerceAtMost(content.data.size)
            
            for (i in startIdx until endIdx) {
                val v = abs(content.data[i].toFloat() / 32768f)
                if (v > maxVal) maxVal = v
            }
            if (maxVal == 0f) return false
            val gain = targetPeak / maxVal
            for (i in startIdx until endIdx) {
                val newVal = (content.data[i] * gain).toInt().coerceIn(-32768, 32767)
                content.data[i] = newVal.toShort()
            }
            onProgress(0.8f)
            return savePCMToAAC(content.data, outputFile, sampleRate, content.channelCount)
        } catch (e: Exception) { return false }
    }
    
    fun mergeFiles(inputs: List<File>, output: File): Boolean {
        if (inputs.isEmpty()) return false
        val allData = ArrayList<Short>()
        var sr = 44100
        var channels = 1
        inputs.forEach { 
            val c = decodeToPCM(it)
            sr = c.sampleRate
            channels = c.channelCount
            for (s in c.data) allData.add(s)
        }
        val arr = ShortArray(allData.size) { allData[it] }
        return savePCMToAAC(arr, output, sr, channels)
    }
}