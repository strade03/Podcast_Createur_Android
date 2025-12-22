package com.podcastcreateur.app

import android.media.*
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import android.media.MediaMetadataRetriever

data class AudioMetadata(val sampleRate: Int, val channelCount: Int, val duration: Long, val totalSamples: Long)
data class AudioContent(val data: ShortArray, val sampleRate: Int, val channelCount: Int)

object AudioHelper {
    private const val BIT_RATE = 128000
    const val POINTS_PER_SECOND = 50 

    // --- UTILITAIRE INTERNE ---
    private fun getDurationAccurate(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val time = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            time?.toLong() ?: 0L
        } catch (e: Exception) { 0L } 
        finally { retriever.release() }
    }

    private fun getWaveformCacheFile(audioFile: File): File {
        return File(audioFile.parent, audioFile.name + ".peak")
    }

    // --- METADONNEES ---
    fun getAudioMetadata(input: File): AudioMetadata? {
        if (!input.exists()) return null
        val duration = getDurationAccurate(input)
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(input.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    val sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    val ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    return AudioMetadata(sr, ch, duration, (duration * sr) / 1000)
                }
            }
        } catch (e: Exception) {} finally { extractor.release() }
        return null
    }

    // --- CHARGEMENT WAVEFORM (STYLE AUDACITY AVEC CACHE) ---
    fun loadWaveform(input: File, onUpdate: (FloatArray) -> Unit) {
        val cacheFile = getWaveformCacheFile(input)
        
        if (cacheFile.exists()) {
            try {
                val bytes = cacheFile.readBytes()
                val floats = FloatArray(bytes.size / 4)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
                onUpdate(floats)
                return 
            } catch (e: Exception) { cacheFile.delete() }
        }

        val extractor = MediaExtractor()
        val allPoints = mutableListOf<Float>()
        try {
            extractor.setDataSource(input.absolutePath)
            val format = (0 until extractor.trackCount)
                .map { extractor.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return
            
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            decoder.configure(format, null, null, 0)
            decoder.start()

            val info = MediaCodec.BufferInfo()
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val samplesPerPoint = (sampleRate * channels) / POINTS_PER_SECOND
            
            val duration = getDurationAccurate(input)
            val step = if (duration > 300_000) 8 else 1 

            var maxPeak = 0f
            var sampleCount = 0
            var isEOS = false
            val tempChunk = FloatArray(1000)
            var chunkIdx = 0

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
                if (outIdx >= 0) {
                    val outBuf = decoder.getOutputBuffer(outIdx)
                    if (outBuf != null) {
                        outBuf.order(ByteOrder.LITTLE_ENDIAN)
                        val shorts = outBuf.asShortBuffer()
                        while (shorts.hasRemaining()) {
                            val s = abs(shorts.get().toFloat() / 32768f)
                            if (s > maxPeak) maxPeak = s
                            sampleCount += step
                            
                            if (step > 1 && shorts.hasRemaining()) {
                                val next = (shorts.position() + step - 1).coerceAtMost(shorts.limit())
                                shorts.position(next)
                            }

                            if (sampleCount >= samplesPerPoint) {
                                val p = maxPeak.coerceAtMost(1.0f)
                                tempChunk[chunkIdx++] = p
                                allPoints.add(p)
                                maxPeak = 0f; sampleCount = 0
                                if (chunkIdx >= tempChunk.size) { onUpdate(tempChunk.copyOf()); chunkIdx = 0 }
                            }
                        }
                    }
                    decoder.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                } else if (isEOS && outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) break
            }
            if (chunkIdx > 0) onUpdate(tempChunk.copyOfRange(0, chunkIdx))
            
            val cacheBuf = ByteBuffer.allocate(allPoints.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            allPoints.forEach { cacheBuf.putFloat(it) }
            cacheFile.writeBytes(cacheBuf.array())

            decoder.stop(); decoder.release(); extractor.release()
        } catch (e: Exception) { e.printStackTrace() }
    }

    // --- DECODAGE MONO-MIX (GAIN RAM 50%) ---
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

            val totalExpectedSamples = ((meta.duration * meta.sampleRate) / 1000).toInt()
            val finalPcm = ShortArray(totalExpectedSamples + 10000)
            var writeOffset = 0
            val info = MediaCodec.BufferInfo()
            var isEOS = false
            val channels = meta.channelCount

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
                val outIdx = decoderDequeue(codec, info)
                if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)
                    if (outBuf != null) {
                        outBuf.order(ByteOrder.LITTLE_ENDIAN)
                        val shorts = outBuf.asShortBuffer()
                        while (shorts.hasRemaining()) {
                            if (channels == 2) {
                                val left = shorts.get().toInt()
                                val right = if (shorts.hasRemaining()) shorts.get().toInt() else left
                                if (writeOffset < finalPcm.size) finalPcm[writeOffset++] = ((left + right) / 2).toShort()
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
        } catch (e: Exception) { return AudioContent(ShortArray(0), 44100, 1) }
    }

    private fun decoderDequeue(codec: MediaCodec, info: MediaCodec.BufferInfo): Int {
        return try { codec.dequeueOutputBuffer(info, 5000) } catch (e: Exception) { -1 }
    }

    // --- ACTIONS D'EDITION ---
    fun generateWaveformFromPCM(content: AudioContent): FloatArray {
        val samplesPerPoint = content.sampleRate / POINTS_PER_SECOND
        if (samplesPerPoint <= 0) return floatArrayOf()
        val pointCount = content.data.size / samplesPerPoint
        val waveform = FloatArray(pointCount)
        for (i in 0 until pointCount) {
            var max = 0f
            val start = i * samplesPerPoint
            for (j in 0 until samplesPerPoint) {
                val idx = start + j
                if (idx < content.data.size) {
                    val s = abs(content.data[idx].toFloat() / 32768f)
                    if (s > max) max = s
                }
            }
            waveform[i] = max
        }
        return waveform
    }

    fun savePCMToAAC(pcmData: ShortArray, outputFile: File, sampleRate: Int, channels: Int): Boolean {
        val cache = getWaveformCacheFile(outputFile)
        if (cache.exists()) cache.delete()

        var enc: MediaCodec? = null
        var mux: MediaMuxer? = null
        try {
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels)
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64000)
            
            enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            
            mux = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var tr = -1; var st = false; val info = MediaCodec.BufferInfo()
            
            val bb = ByteBuffer.allocate(pcmData.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            bb.asShortBuffer().put(pcmData)
            val all = bb.array(); var off = 0; var eos = false
            
            while (true) {
                if (!eos) {
                    val i = enc.dequeueInputBuffer(5000)
                    if (i >= 0) {
                        val rem = all.size - off
                        val len = minOf(rem, 16384)
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
                val o = enc.dequeueOutputBuffer(info, 5000)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    tr = mux.addTrack(enc.outputFormat); mux.start(); st = true
                } else if (o >= 0) {
                    if (info.size != 0 && st) {
                        val d = enc.getOutputBuffer(o)
                        mux.writeSampleData(tr, d!!, info)
                    }
                    enc.releaseOutputBuffer(o, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            return true
        } catch (e: Exception) { e.printStackTrace(); return false } 
        finally { try { enc?.release(); mux?.release() } catch (e: Exception) {} }
    }

    // --- FONCTIONS MANQUANTES (FIX COMPILATION) ---
    
    fun normalizeAudio(inputFile: File, outputFile: File, startMs: Long, endMs: Long, sampleRate: Int, targetPeak: Float, onProgress: (Float) -> Unit): Boolean {
        val content = decodeToPCM(inputFile)
        if (content.data.isEmpty()) return false
        onProgress(0.5f)
        var maxVal = 0f
        val startIdx = ((startMs * sampleRate) / 1000).toInt()
        val endIdx = ((endMs * sampleRate) / 1000).toInt().coerceAtMost(content.data.size)
        for (i in startIdx until endIdx) {
            val v = abs(content.data[i].toFloat() / 32768f)
            if (v > maxVal) maxVal = v
        }
        if (maxVal == 0f) return false
        val gain = targetPeak / maxVal
        for (i in startIdx until endIdx) {
            content.data[i] = (content.data[i] * gain).toInt().coerceIn(-32768, 32767).toShort()
        }
        onProgress(0.8f)
        return savePCMToAAC(content.data, outputFile, sampleRate, 1)
    }

    fun mergeFiles(inputs: List<File>, output: File): Boolean {
        if (inputs.isEmpty()) return false
        val allData = mutableListOf<ShortArray>()
        var sr = 44100
        var totalSize = 0
        inputs.forEach { 
            val c = decodeToPCM(it)
            sr = c.sampleRate
            allData.add(c.data)
            totalSize += c.data.size
        }
        val finalArr = ShortArray(totalSize)
        var offset = 0
        allData.forEach {
            System.arraycopy(it, 0, finalArr, offset, it.size)
            offset += it.size
        }
        return savePCMToAAC(finalArr, output, sr, 1)
    }
}