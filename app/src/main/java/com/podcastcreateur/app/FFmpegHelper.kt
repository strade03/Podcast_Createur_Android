package com.podcastcreateur.app

import android.util.Log
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.ReturnCode
import java.io.File
import java.io.FileWriter

object FFmpegHelper {
    private const val TAG = "FFmpegHelper"

    /**
     * Fusionne une liste de fichiers audio en utilisant le Concat Demuxer (Stream Copy).
     * C'est extrêmement rapide car cela ne ré-encode pas l'audio.
     */
    fun mergeAudioFiles(inputs: List<File>, output: File, callback: (Boolean) -> Unit) {
        if (inputs.isEmpty()) {
            callback(false)
            return
        }

        Thread {
            try {
                // 1. Créer le fichier liste pour ffmpeg
                val listFile = File(output.parent, "ffmpeg_concat_list.txt")
                val writer = FileWriter(listFile)
                inputs.forEach { file ->
                    // On échappe les apostrophes pour ffmpeg
                    writer.write("file '${file.absolutePath.replace("'", "'\\''")}'\n")
                }
                writer.close()

                // 2. Commande Concat (safe 0 pour accepter les chemins absolus)
                // -c copy : Copie de flux sans réencodage (Instantané)
                val cmd = "-f concat -safe 0 -i \"${listFile.absolutePath}\" -c copy -y \"${output.absolutePath}\""
                
                Log.d(TAG, "Executing merge: $cmd")
                
                val session = FFmpegKit.execute(cmd)
                
                // Nettoyage
                if (listFile.exists()) listFile.delete()

                if (ReturnCode.isSuccess(session.returnCode)) {
                    callback(true)
                } else {
                    Log.e(TAG, "Merge failed: " + session.failStackTrace)
                    callback(false)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                callback(false)
            }
        }.start()
    }

    /**
     * Coupe un fichier audio.
     * startSec : début en secondes
     * durationSec : durée à garder
     */
    fun cutAudio(input: File, output: File, startSec: Double, durationSec: Double, callback: (Boolean) -> Unit) {
        Thread {
            // -ss : Start time
            // -t : Duration
            // On ré-encode en AAC pour éviter les soucis de coupe sur keyframes imprécises
            val cmd = "-ss $startSec -t $durationSec -i \"${input.absolutePath}\" -c:a aac -b:a 128k -y \"${output.absolutePath}\""
            
            Log.d(TAG, "Executing cut: $cmd")
            val session = FFmpegKit.execute(cmd)
            
            if (ReturnCode.isSuccess(session.returnCode)) {
                callback(true)
            } else {
                Log.e(TAG, "Cut failed: " + session.failStackTrace)
                callback(false)
            }
        }.start()
    }

    /**
     * Normalise le volume (utilise loudnorm ou un gain simple).
     * Ici on utilise un filtre de volume simple pour la rapidité sur mobile, 
     * mais loudnorm est possible ("-filter:a loudnorm") si la lib ffmpeg-min l'inclut.
     */
    fun normalizeAudio(input: File, output: File, callback: (Boolean) -> Unit) {
        Thread {
            // Utilisation de loudnorm pour une vraie normalisation (EBU R128)
            // Si loudnorm plante avec la version min, remplacer par "volume=1.5" par exemple
            val cmd = "-i \"${input.absolutePath}\" -filter:a loudnorm -c:a aac -b:a 128k -y \"${output.absolutePath}\""
            
            Log.d(TAG, "Executing normalize: $cmd")
            val session = FFmpegKit.execute(cmd)
            
            if (ReturnCode.isSuccess(session.returnCode)) {
                callback(true)
            } else {
                // Fallback si loudnorm n'est pas dispo dans la version min -> Gain automatique simple
                Log.w(TAG, "Loudnorm failed, trying simple volume boost")
                val fallbackCmd = "-i \"${input.absolutePath}\" -filter:a volume=3dB -c:a aac -b:a 128k -y \"${output.absolutePath}\""
                val session2 = FFmpegKit.execute(fallbackCmd)
                callback(ReturnCode.isSuccess(session2.returnCode))
            }
        }.start()
    }
}
