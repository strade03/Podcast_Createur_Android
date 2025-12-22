package com.podcastcreateur.app

import android.media.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.*

data class AudioMetadata(
    val sampleRate: Int,
    val channelCount: Int,
    val duration: Long, 
    val totalSamples: Long
)

data class AudioContent(
    val data: ShortArray,
    val sampleRate: Int
)

// Nouveau : Cache de waveform pour éviter de recalculer
data class WaveformCache(
    val filePath: String,
    val lastModified: Long,
    val waveform: FloatArray
)

object AudioHelper {
    private const val BIT_RATE = 128000
    const val POINTS_PER_SECOND = 50 
    
    // Cache simple (1 fichier max pour l'instant)
    private var waveformCache: WaveformCache? = null
    
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
            
            val totalSamples = (accurateDuration * sampleRate) / 1000
            
            return AudioMetadata(sampleRate, channelCount, accurateDuration, totalSamples)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            extractor.release()
        }
    }

    /**
     * ⚡ OPTIMISATION MAJEURE : Génération waveform avec streaming + cache
     * Inspiré d'Audacity : traitement par chunks avec feedback progressif
     */
    fun loadWaveformStreamOptimized(
        input: File, 
        onUpdate: (FloatArray) -> Unit,
        onComplete: () -> Unit = {}
    ) {
        if (!input.exists()) { 
            onUpdate(FloatArray(0))
            onComplete()
            return 
        }
        
        // ✅ Vérifier le cache d'abord
        val cached = waveformCache
        if (cached != null && 
            cached.filePath == input.absolutePath && 
            cached.lastModified == input.lastModified()) {
            // Cache hit ! Instantané
            onUpdate(cached.waveform)
            onComplete()
            return
        }
        
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        
        try {
            extractor.setDataSource(input.absolutePath)
            var idx = -1
            var fmt: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { 
                    idx = i
                    fmt = f
                    break 
                }
            }
            if (idx < 0 || fmt == null) {
                onComplete()
                return
            }
            
            extractor.selectTrack(idx)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(fmt, null, null, 0)
            decoder.start()
            
            val info = MediaCodec.BufferInfo()
            
            // ✅ OPTIMISATION 1 : ArrayList pré-allouée (évite resize)
            val estimatedPoints = ((getDurationAccurate(input) / 1000) * POINTS_PER_SECOND).toInt()
            val allPoints = ArrayList<Float>(estimatedPoints)
            
            var maxPeak = 0f
            var count = 0
            var isEOS = false
            
            var currentSampleRate = 44100
            var currentChannels = 1
            var samplesPerPoint = currentSampleRate / POINTS_PER_SECOND
            
            // ✅ OPTIMISATION 2 : Buffer de sortie plus large (réduit les callbacks)
            val outputBuffer = ArrayList<Float>(500) // Au lieu de 200
            
            var lastUpdateTime = System.currentTimeMillis()
            val UPDATE_INTERVAL_MS = 100 // Update UI tous les 100ms max
            
            while (true) {
                // Décodage input
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
                
                // Traitement output
                val outIdx = decoder.dequeueOutputBuffer(info, 5000)
                
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = decoder.outputFormat
                    currentSampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    currentChannels = if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    } else 1
                    
                    samplesPerPoint = (currentSampleRate * currentChannels) / POINTS_PER_SECOND
                }
                
                if (outIdx >= 0) {
                    val outBuf = decoder.getOutputBuffer(outIdx)
                    if (outBuf != null && info.size > 0) {
                        outBuf.order(ByteOrder.LITTLE_ENDIAN)
                        val shorts = outBuf.asShortBuffer()
                        
                        // ✅ OPTIMISATION 3 : Traitement par blocs
                        while (shorts.hasRemaining()) {
                            val sample = abs(shorts.get().toFloat() / 32768f)
                            if (sample > maxPeak) maxPeak = sample
                            count++
                            
                            if (count >= samplesPerPoint) {
                                val point = maxPeak.coerceAtMost(1.0f)
                                outputBuffer.add(point)
                                allPoints.add(point)
                                maxPeak = 0f
                                count = 0
                                
                                // ✅ OPTIMISATION 4 : Updates throttlés (évite spam UI)
                                if (outputBuffer.size >= 500) {
                                    val now = System.currentTimeMillis()
                                    if (now - lastUpdateTime >= UPDATE_INTERVAL_MS) {
                                        onUpdate(outputBuffer.toFloatArray())
                                        outputBuffer.clear()
                                        lastUpdateTime = now
                                    }
                                }
                            }
                        }
                    }
                    decoder.releaseOutputBuffer(outIdx, false)
                    
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER && isEOS) {
                    break
                }
            }
            
            // Flush final
            if (outputBuffer.isNotEmpty()) {
                onUpdate(outputBuffer.toFloatArray())
            }
            
            // ✅ OPTIMISATION 5 : Mise en cache
            val finalWaveform = allPoints.toFloatArray()
            waveformCache = WaveformCache(
                input.absolutePath,
                input.lastModified(),
                finalWaveform
            )
            
            onComplete()
            
        } catch (e: Exception) { 
            e.printStackTrace()
            onComplete()
        } finally { 
            try {
                decoder?.stop()
                decoder?.release()
            } catch (e: Exception) {}
            extractor.release()
        }
    }

    /**
     * ⚡ NOUVEAU : Chargement paresseux du PCM (uniquement quand nécessaire)
     * Utilisé uniquement pour l'édition, pas pour l'affichage
     */
    suspend fun decodeToPCMLazy(input: File): AudioContent = withContext(Dispatchers.IO) {
        if (!input.exists()) return@withContext AudioContent(ShortArray(0), 44100)
        
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        
        try {
            extractor.setDataSource(input.absolutePath)
            var idx = -1
            var fmt: MediaFormat? = null
            
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    idx = i
                    fmt = f
                    break
                }
            }
            
            if (idx < 0 || fmt == null) return@withContext AudioContent(ShortArray(0), 44100)
            
            extractor.selectTrack(idx)
            decoder = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            decoder.configure(fmt, null, null, 0)
            decoder.start()
            
            val info = MediaCodec.BufferInfo()
            val baos = java.io.ByteArrayOutputStream()
            var rate = 44100
            var eos = false
            
            while (true) {
                if (!eos) {
                    val i = decoder.dequeueInputBuffer(10000)
                    if (i >= 0) {
                        val b = decoder.getInputBuffer(i)
                        val s = extractor.readSampleData(b!!, 0)
                        if (s < 0) {
                            decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eos = true
                        } else {
                            decoder.queueInputBuffer(i, 0, s, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                
                val o = decoder.dequeueOutputBuffer(info, 10000)
                
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    rate = decoder.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                }
                
                if (o >= 0) {
                    val b = decoder.getOutputBuffer(o)
                    if (b != null && info.size > 0) {
                        val c = ByteArray(info.size)
                        b.get(c)
                        baos.write(c)
                    }
                    decoder.releaseOutputBuffer(o, false)
                    
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                } else if (o == MediaCodec.INFO_TRY_AGAIN_LATER && eos) {
                    break
                }
            }
            
            val by = baos.toByteArray()
            val sh = ShortArray(by.size / 2)
            ByteBuffer.wrap(by).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(sh)
            
            AudioContent(sh, rate)
            
        } catch (e: Exception) {
            e.printStackTrace()
            AudioContent(ShortArray(0), 44100)
        } finally {
            try {
                decoder?.stop()
                decoder?.release()
            } catch (e: Exception) {}
            extractor.release()
        }
    }

    // ✅ Garde l'ancienne fonction pour compatibilité
    fun decodeToPCM(input: File): AudioContent {
        return runBlocking { decodeToPCMLazy(input) }
    }

    // --- RESTE DU CODE INCHANGÉ ---
    
    fun deleteRegionStreaming(input: File, output: File, startSample: Int, endSample: Int): Boolean {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(input.absolutePath)
            var trackIdx = -1
            var format: MediaFormat? = null
            for(i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if(f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true) { trackIdx=i; format=f; break }
            }
            if(trackIdx<0 || format==null) return false
            extractor.selectTrack(trackIdx)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()

            val encMime = MediaFormat.MIMETYPE_AUDIO_AAC
            val encFormat = MediaFormat.createAudioFormat(encMime, sampleRate, 1)
            encFormat.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            encFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            encFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            
            encoder = MediaCodec.createEncoderByType(encMime)
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            
            val bufferInfo = MediaCodec.BufferInfo()
            val encBufferInfo = MediaCodec.BufferInfo()
            
            var muxerTrackIndex = -1
            var muxerStarted = false
            var totalSamplesProcessed = 0L
            var isInputEOS = false
            var isDecodedEOS = false
            
            while (true) {
                if (!isInputEOS) {
                    val inIdx = decoder.dequeueInputBuffer(2000)
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

                if (!isDecodedEOS) {
                    val outIdx = decoder.dequeueOutputBuffer(bufferInfo, 2000)
                    if (outIdx >= 0) {
                        val outBuf = decoder.getOutputBuffer(outIdx)
                        if (outBuf != null && bufferInfo.size > 0) {
                            val chunkBytes = ByteArray(bufferInfo.size)
                            outBuf.position(bufferInfo.offset)
                            outBuf.get(chunkBytes)
                            val samplesInChunk = chunkBytes.size / 2
                            
                            val keptBytesStream = java.io.ByteArrayOutputStream()
                            for (i in 0 until samplesInChunk) {
                                val currentSampleGlobal = totalSamplesProcessed + i
                                if (currentSampleGlobal < startSample || currentSampleGlobal >= endSample) {
                                    keptBytesStream.write(chunkBytes[i*2].toInt())
                                    keptBytesStream.write(chunkBytes[i*2+1].toInt())
                                }
                            }
                            totalSamplesProcessed += samplesInChunk
                            val keptBytes = keptBytesStream.toByteArray()
                            if (keptBytes.isNotEmpty()) {
                                feedEncoder(encoder, keptBytes)
                            }
                        }
                        decoder.releaseOutputBuffer(outIdx, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            isDecodedEOS = true
                            val inIdx = encoder.dequeueInputBuffer(2000)
                            if (inIdx >= 0) encoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        }
                    }
                }

                val encOutIdx = encoder.dequeueOutputBuffer(encBufferInfo, 2000)
                if (encOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    muxerTrackIndex = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    muxerStarted = true
                } else if (encOutIdx >= 0) {
                    val encodedData = encoder.getOutputBuffer(encOutIdx)
                    if (encBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) encBufferInfo.size = 0
                    if (encBufferInfo.size != 0 && muxerStarted) {
                        encodedData?.position(encBufferInfo.offset)
                        encodedData?.limit(encBufferInfo.offset + encBufferInfo.size)
                        muxer.writeSampleData(muxerTrackIndex, encodedData!!, encBufferInfo)
                    }
                    encoder.releaseOutputBuffer(encOutIdx, false)
                    if (encBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        } finally {
            try { decoder?.stop(); decoder?.release() } catch(e:Exception){}
            try { encoder?.stop(); encoder?.release() } catch(e:Exception){}
            try { muxer?.stop(); muxer?.release() } catch(e:Exception){}
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

    fun generateWaveformFromPCM(pcmData: ShortArray, sampleRate: Int): FloatArray {
        val samplesPerPoint = sampleRate / POINTS_PER_SECOND
        if (samplesPerPoint == 0) return floatArrayOf()
        
        val pointCount = pcmData.size / samplesPerPoint
        val waveform = FloatArray(pointCount)
        
        for (i in 0 until pointCount) {
            var max = 0f
            for (j in 0 until samplesPerPoint) {
                val idx = i * samplesPerPoint + j
                if (idx >= pcmData.size) break
                val sample = abs(pcmData[idx].toFloat() / 32768f)
                if (sample > max) max = sample
            }
            waveform[i] = max.coerceAtMost(1.0f)
        }
        return waveform
    }
    
    fun savePCMToAAC(pcmData: ShortArray, outputFile: File, sampleRate: Int): Boolean {
        var enc:MediaCodec?=null; var mux:MediaMuxer?=null; try{
            val fmt=MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,sampleRate,1)
            fmt.setInteger(MediaFormat.KEY_BIT_RATE,BIT_RATE); fmt.setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC); fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,16384)
            enc=MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC); enc.configure(fmt,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE); enc.start()
            mux=MediaMuxer(outputFile.absolutePath,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4); var tr=-1; var st=false; val info=MediaCodec.BufferInfo()
            val bb=ByteBuffer.allocate(pcmData.size*2).order(ByteOrder.LITTLE_ENDIAN); for(s in pcmData)bb.putShort(s); bb.position(0); val all=bb.array(); var off=0; var eos=false
            while(true){
                if(!eos){val i=enc.dequeueInputBuffer(1000);if(i>=0){val rem=all.size-off;val len=if(rem>4096)4096 else rem;if(len>0){enc.getInputBuffer(i)?.put(all,off,len);off+=len;enc.queueInputBuffer(i,0,len,System.nanoTime()/1000,0)}else{enc.queueInputBuffer(i,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);eos=true}}}
                val o=enc.dequeueOutputBuffer(info,1000); if(o==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){tr=mux.addTrack(enc.outputFormat);mux.start();st=true}
                else if(o>=0){if(info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG!=0)info.size=0;if(info.size!=0&&st){val d=enc.getOutputBuffer(o);d?.position(info.offset);d?.limit(info.offset+info.size);mux.writeSampleData(tr,d!!,info)};enc.releaseOutputBuffer(o,false);if(info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM!=0)break}
            }
            return true
        }catch(e:Exception){e.printStackTrace();return false}finally{try{enc?.release();mux?.release()}catch(e:Exception){}}
    }
    
    fun normalizeAudio(inputFile: File, outputFile: File, startMs: Long, endMs: Long, sampleRate: Int, targetPeak: Float, onProgress: (Float)->Unit): Boolean {
        try {
            val content = decodeToPCM(inputFile)
            onProgress(0.5f)
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
    
    // ✅ NOUVEAU : Invalider le cache si le fichier change
    fun invalidateCache(filePath: String) {
        if (waveformCache?.filePath == filePath) {
            waveformCache = null
        }
    }
}