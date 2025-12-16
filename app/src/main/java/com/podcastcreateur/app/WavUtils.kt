package com.podcastcreateur.app



import java.io.*

import java.nio.ByteBuffer

import java.nio.ByteOrder



object WavUtils {

    private const val HEADER_SIZE = 44

    const val RECORDER_SAMPLE_RATE = 44100

    const val RECORDER_CHANNELS = 1

    const val RECORDER_AUDIO_ENCODING = android.media.AudioFormat.ENCODING_PCM_16BIT



    /**

     * Fusionne une liste de fichiers WAV en un seul fichier de sortie.

     * Ignore les headers des fichiers d'entrÃ©e pour ne garder que les donnÃ©es PCM.

     */

    fun mergeFiles(inputs: List<File>, output: File): Boolean {

        if (inputs.isEmpty()) return false



        try {

            val outputStream = FileOutputStream(output)

            var totalDataLen: Long = 0



            // 1. Ã‰crire un header vide (placeholder)

            writeWavHeader(outputStream, 0, 0, RECORDER_SAMPLE_RATE, RECORDER_CHANNELS)



            // 2. ConcatÃ©ner les donnÃ©es brutes

            val buffer = ByteArray(4096)

            for (file in inputs) {

                if (!file.exists() || file.length() < HEADER_SIZE) continue



                val fis = FileInputStream(file)

                // On saute les 44 octets du header du fichier source

                // (Note: Pour Ãªtre robuste, il faudrait lire le header pour savoir sa taille rÃ©elle,

                // mais pour nos enregistrements internes, c'est toujours 44).

                fis.skip(HEADER_SIZE.toLong())

                

                var bytesRead: Int

                while (fis.read(buffer).also { bytesRead = it } != -1) {

                    outputStream.write(buffer, 0, bytesRead)

                    totalDataLen += bytesRead

                }

                fis.close()

            }



            outputStream.close()



            // 3. Mettre Ã  jour le header avec la taille finale

            updateHeader(output, totalDataLen, RECORDER_SAMPLE_RATE, RECORDER_CHANNELS)

            return true

        } catch (e: Exception) {

            e.printStackTrace()

            return false

        }

    }



    /**

     * Sauvegarde un tableau de Short (PCM) en fichier WAV.

     * Utile aprÃ¨s l'Ã©dition.

     */

    fun savePcmToWav(pcmData: ShortArray, output: File) {

        try {

            val outputStream = FileOutputStream(output)

            val totalDataLen = pcmData.size * 2L

            

            writeWavHeader(outputStream, totalDataLen, totalDataLen + 36, RECORDER_SAMPLE_RATE, RECORDER_CHANNELS)

            

            // Conversion ShortArray -> ByteArray (Little Endian)

            val buffer = ByteBuffer.allocate(pcmData.size * 2)

            buffer.order(ByteOrder.LITTLE_ENDIAN)

            for (s in pcmData) {

                buffer.putShort(s)

            }

            outputStream.write(buffer.array())

            outputStream.close()

        } catch (e: Exception) {

            e.printStackTrace()

        }

    }



    /**

     * Met Ã  jour la taille dans le header d'un fichier WAV existant.

     */

    fun updateHeader(file: File, audioLen: Long, sampleRate: Int, channels: Int) {

        try {

            val raf = RandomAccessFile(file, "rw")

            raf.seek(0)

            val totalDataLen = audioLen + 36

            val byteRate = sampleRate * 16 * channels / 8



            // On rÃ©Ã©crit tout le header pour Ãªtre sÃ»r

            val header = ByteArray(44)

            header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()

            header[4] = (totalDataLen and 0xff).toByte()

            header[5] = (totalDataLen shr 8 and 0xff).toByte()

            header[6] = (totalDataLen shr 16 and 0xff).toByte()

            header[7] = (totalDataLen shr 24 and 0xff).toByte()

            header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()

            header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()

            header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0

            header[20] = 1; header[21] = 0

            header[22] = channels.toByte(); header[23] = 0

            header[24] = (sampleRate and 0xff).toByte()

            header[25] = (sampleRate shr 8 and 0xff).toByte()

            header[26] = (sampleRate shr 16 and 0xff).toByte()

            header[27] = (sampleRate shr 24 and 0xff).toByte()

            header[28] = (byteRate and 0xff).toByte()

            header[29] = (byteRate shr 8 and 0xff).toByte()

            header[30] = (byteRate shr 16 and 0xff).toByte()

            header[31] = (byteRate shr 24 and 0xff).toByte()

            header[32] = (channels * 16 / 8).toByte(); header[33] = 0

            header[34] = 16; header[35] = 0

            header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()

            header[40] = (audioLen and 0xff).toByte()

            header[41] = (audioLen shr 8 and 0xff).toByte()

            header[42] = (audioLen shr 16 and 0xff).toByte()

            header[43] = (audioLen shr 24 and 0xff).toByte()



            raf.write(header)

            raf.close()

        } catch (e: Exception) {

            e.printStackTrace()

        }

    }



    fun writeWavHeader(out: OutputStream, totalAudioLen: Long, totalDataLen: Long, sampleRate: Int, channels: Int) {

        val header = ByteArray(44)

        val byteRate = sampleRate * 16 * channels / 8

        

        // ... (MÃªme logique d'Ã©criture que ci-dessus, factorisÃ©e) ...

        // Pour raccourcir le code ici, assumez que c'est la copie du bloc ci-dessus

        // mais Ã©crivant dans 'out' au lieu de 'raf'.

        

        // RIFF

        out.write("RIFF".toByteArray())

        out.write(intToByteArray(totalDataLen.toInt()), 0, 4)

        out.write("WAVEfmt ".toByteArray())

        out.write(intToByteArray(16), 0, 4) // PCM Header Size

        out.write(shortToByteArray(1), 0, 2) // AudioFormat 1 = PCM

        out.write(shortToByteArray(channels.toShort()), 0, 2)

        out.write(intToByteArray(sampleRate), 0, 4)

        out.write(intToByteArray(byteRate), 0, 4)

        out.write(shortToByteArray((channels * 16 / 8).toShort()), 0, 2) // BlockAlign

        out.write(shortToByteArray(16), 0, 2) // BitsPerSample

        out.write("data".toByteArray())

        out.write(intToByteArray(totalAudioLen.toInt()), 0, 4)

    }



    private fun intToByteArray(data: Int): ByteArray {

        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(data).array()

    }

    private fun shortToByteArray(data: Short): ByteArray {

        return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(data).array()

    }



    /**

     * Lit les donnÃ©es PCM (sans header) pour l'affichage Waveform

     */

    fun readWavData(file: File): ShortArray {

        if (!file.exists()) return ShortArray(0)

        val bytes = file.readBytes()

        if (bytes.size < HEADER_SIZE) return ShortArray(0)



        // Extraction brute

        val shortArr = ShortArray((bytes.size - HEADER_SIZE) / 2)

        ByteBuffer.wrap(bytes, HEADER_SIZE, bytes.size - HEADER_SIZE)

            .order(ByteOrder.LITTLE_ENDIAN)

            .asShortBuffer()

            .get(shortArr)

        return shortArr

    }

}
