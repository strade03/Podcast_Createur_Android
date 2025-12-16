package com.podcastcreateur.app

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File

object FFmpegHelper {
    private const val TAG = "FFmpegHelper"

    /**
     * Fusionne une liste de fichiers audio en utilisant le filtre CONCAT (filter_complex).
     * C'est beaucoup plus robuste que le Concat Demuxer (fichier texte) pour la version MIN
     * et gère mieux les problèmes de format.
     */
    fun mergeAudioFiles(inputs: List<File>, output: File, callback: (Boolean) -> Unit) {
        if (inputs.isEmpty()) {
            callback(false)
            return
        }

        Thread {
            try {
                // Construction de la commande complexe
                // Exemple pour 2 fichiers : 
                // -i f1 -i f2 -filter_complex "[0:a][1:a]concat=n=2:v=0:a=1[out]" -map "[out]" output.m4a
                
                val cmdBuilder = StringBuilder()
                
                // 1. Ajouter les inputs
                inputs.forEach { file ->
                    cmdBuilder.append("-i \"${file.absolutePath}\" ")
                }
                
                // 2. Construire le filter graph
                cmdBuilder.append("-filter_complex \"")
                for (i in inputs.indices) {
                    cmdBuilder.append("[$i:a]")
                }
                cmdBuilder.append("concat=n=${inputs.size}:v=0:a=1[out]\" ")
                
                // 3. Mapping et Encodage
                // -map "[out]" : Sélectionne le résultat du filtre
                // -c:a aac : Encode en AAC
                // -b:a 128k : Qualité
                // -y : Overwrite
                cmdBuilder.append("-map \"[out]\" -c:a aac -b:a 128k -y \"${output.absolutePath}\"")
                
                val cmd = cmdBuilder.toString()
                
                Log.d(TAG, "Executing Merge (Filter Complex): $cmd")
                
                val session = FFmpegKit.execute(cmd)
                
                if (ReturnCode.isSuccess(session.returnCode)) {
                    Log.d(TAG, "Merge success")
                    callback(true)
                } else {
                    Log.e(TAG, "Merge failed. Logs: " + session.allLogsAsString)
                    callback(false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during merge", e)
                callback(false)
            }
        }.start()
    }

    /**
     * Coupe un fichier audio avec précision.
     * Utilise le ré-encodage pour garantir la précision à la milliseconde.
     */
    fun cutAudio(input: File, output: File, startSec: Double, durationSec: Double, callback: (Boolean) -> Unit) {
        Thread {
            val cmd = "-ss $startSec -t $durationSec -i \"${input.absolutePath}\" -c:a aac -b:a 128k -y \"${output.absolutePath}\""
            
            Log.d(TAG, "Executing cut: $cmd")
            val session = FFmpegKit.execute(cmd)
            
            if (ReturnCode.isSuccess(session.returnCode)) {
                callback(true)
            } else {
                Log.e(TAG, "Cut failed. Logs: " + session.allLogsAsString)
                callback(false)
            }
        }.start()
    }

    /**
     * Normalise le volume.
     */
    fun normalizeAudio(input: File, output: File, callback: (Boolean) -> Unit) {
        Thread {
            // Tentative loudnorm
            val cmd = "-i \"${input.absolutePath}\" -filter:a loudnorm=I=-16:TP=-1.5:LRA=11 -c:a aac -b:a 128k -y \"${output.absolutePath}\""
            
            Log.d(TAG, "Executing normalize: $cmd")
            val session = FFmpegKit.execute(cmd)
            
            if (ReturnCode.isSuccess(session.returnCode)) {
                callback(true)
            } else {
                Log.w(TAG, "Loudnorm failed, trying simple volume boost")
                val fallbackCmd = "-i \"${input.absolutePath}\" -filter:a volume=3dB -c:a aac -b:a 128k -y \"${output.absolutePath}\""
                val session2 = FFmpegKit.execute(fallbackCmd)
                callback(ReturnCode.isSuccess(session2.returnCode))
            }
        }.start()
    }
}