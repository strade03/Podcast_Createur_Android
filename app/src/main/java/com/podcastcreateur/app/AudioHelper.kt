package com.podcastcreateur.app



import android.media.*

import java.io.File

import java.nio.ByteBuffer

import java.nio.ByteOrder



data class AudioContent(

    val data: ShortArray,

    val sampleRate: Int

)



object AudioHelper {

    private const val BIT_RATE = 128000



    /**

     * DÃ©code le fichier et renvoie les donnÃ©es MONO avec leur frÃ©quence d'origine.

     * CORRECTION : Gestion correcte du nombre de canaux (stÃ©rÃ©o -> mono)

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



        // RÃ©cupÃ©ration de la frÃ©quence d'Ã©chantillonnage

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

        var channelCount = 1  // NOUVEAU : On stocke le nombre de canaux

        

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

                

                // CORRECTION : Capturer le nombre de canaux ET la frÃ©quence

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

        

        // CORRECTION MAJEURE : Si c'est du stÃ©rÃ©o, on convertit en mono

        val monoShorts = if (channelCount == 2) {

            convertStereoToMono(shorts)

        } else {

            shorts

        }

        

        return AudioContent(monoShorts, actualSampleRate)

    }



    /**

     * NOUVELLE FONCTION : Convertit stÃ©rÃ©o en mono (moyenne des 2 canaux)

     */

    private fun convertStereoToMono(stereo: ShortArray): ShortArray {

        val mono = ShortArray(stereo.size / 2)

        for (i in mono.indices) {

            val left = stereo[i * 2].toInt()

            val right = stereo[i * 2 + 1].toInt()

            mono[i] = ((left + right) / 2).toShort()

        }

        return mono

    }



    /**

     * Interpolation linÃ©aire pour changer la vitesse/frÃ©quence

     */

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



    /**

     * Fusion Intelligente : Prend la frÃ©quence du premier fichier comme MaÃ®tre.

     * Si un fichier suivant a une frÃ©quence diffÃ©rente, on le convertit.

     */

    fun mergeFiles(inputs: List<File>, output: File): Boolean {

        if (inputs.isEmpty()) return false

        try {

            val allSamples = ArrayList<Short>()

            var masterSampleRate = 44100

            var isFirst = true

            

            for (file in inputs) {

                val content = decodeToPCM(file)

                

                if (isFirst) {

                    // Le premier fichier dicte la loi

                    masterSampleRate = content.sampleRate

                    for (s in content.data) allSamples.add(s)

                    isFirst = false

                } else {

                    // Les suivants doivent s'adapter

                    if (content.sampleRate != masterSampleRate) {

                        // On convertit pour matcher le maitre

                        val resampledData = resample(content.data, content.sampleRate, masterSampleRate)

                        for (s in resampledData) allSamples.add(s)

                    } else {

                        // FrÃ©quence identique, on ajoute direct

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
