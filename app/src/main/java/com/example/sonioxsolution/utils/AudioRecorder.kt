package com.example.sonioxsolution.utils

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

class AudioRecorder {

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null

    // Channel to send audio chunks to the consumer (e.g., WebSocket)
    val audioChannel = Channel<ByteArray>(Channel.UNLIMITED)

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startRecording(scope: CoroutineScope) {
        val bufferSize = AudioRecord.getMinBufferSize(
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        audioRecord?.startRecording()

        recordingJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(bufferSize)
            while (isActive) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    audioChannel.send(buffer.copyOf(read))
                }
            }
        }
    }

    fun stopRecording() {
        recordingJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        audioChannel.close()
    }
}
