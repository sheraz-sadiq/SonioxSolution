package com.example.sonioxsolution.utils

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class ElevenLabsTTS(private val context: Context) {
    private val TAG = "ElevenLabsTTS"
    private val apiKey = "sk_c5618a26102cc48c554f354799544c3eebe48da39659561f"
    private val baseUrl = "https://api.elevenlabs.io/v1"

    // Voice IDs for different speakers
    private val voiceId1 = "wgHvco1wiREKN0BdyVx5" // Speaker 1 voice
    private val voiceId2 = "JBFqnCBsd6RMkjVDRZzb" // Speaker 2 voice - using the same as requested

    private val client = OkHttpClient()
    private var currentMediaPlayer: MediaPlayer? = null
    private var currentPlayingPosition: Int = -1

    suspend fun textToSpeech(
        text: String,
        speaker: String,
        position: Int,
        onStart: () -> Unit,
        onComplete: () -> Unit,
        onError: (String)        -> Unit
    ) {
        Log.d(TAG, "TTS Request - Speaker: $speaker, Position: $position, Input text: '$text'")
        Log.d(TAG, "TTS Request - Text length: ${text.length}")

        if (text.isBlank()) {
            Log.e(TAG, "TTS Request - Text is empty or blank")
            onError("Text is empty")
            return
        }

        // Stop any currently playing audio
        stopCurrentPlayback()

        withContext(Dispatchers.Main) {
            onStart()
        }

        try {
            currentPlayingPosition = position
            Log.d(TAG, "TTS Request - Generating speech for speaker $speaker: '$text'")
            val audioBytes = generateSpeech(text, speaker)
            Log.d(TAG, "TTS Request - Audio generated successfully, size: ${audioBytes.size} bytes")

            playAudio(audioBytes, position, onComplete, onError)

        } catch (e: Exception) {
            Log.e(TAG, "TTS Error for text '$text': ${e.message}")
            e.printStackTrace()
            currentPlayingPosition = -1
            withContext(Dispatchers.Main) {
                onError(e.message ?: "Unknown error")
            }
        }
    }

    private suspend fun generateSpeech(text: String, speaker: String): ByteArray = withContext(Dispatchers.IO) {
        // Select voice ID based on speaker
        val voiceId = if (speaker == "1") voiceId1 else voiceId2
        val url = "$baseUrl/text-to-speech/$voiceId"

        Log.d(TAG, "Using voice ID: $voiceId for speaker: $speaker")

        val json = JSONObject().apply {
            put("text", text)
            put("model_id", "eleven_multilingual_v2") // Use multilingual model like your Python test
            put("voice_settings", JSONObject().apply {
                put("stability", 0.5)
                put("similarity_boost", 0.8) // Increased for better quality
                put("style", 0.0)
                put("use_speaker_boost", true)
            })
            put("output_format", "mp3_44100_128") // Same format as Python test
        }

        Log.d(TAG, "TTS Request JSON: ${json.toString()}")

        val requestBody = json.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(url)
            .addHeader("Accept", "audio/mpeg")
            .addHeader("Content-Type", "application/json")
            .addHeader("xi-api-key", apiKey)
            .post(requestBody)
            .build()

        Log.d(TAG, "Making TTS request to: $url")
        Log.d(TAG, "Request headers: ${request.headers}")

        val response = client.newCall(request).execute()

        Log.d(TAG, "TTS API Response code: ${response.code}")
        Log.d(TAG, "TTS API Response message: ${response.message}")

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "No error body"
            Log.e(TAG, "TTS API Error body: $errorBody")
            throw IOException("TTS API request failed: ${response.code} - ${response.message}")
        }

        val audioBytes = response.body?.bytes() ?: throw IOException("Empty response body")
        Log.d(TAG, "Successfully received audio data: ${audioBytes.size} bytes")
        audioBytes
    }

    private suspend fun playAudio(
        audioBytes: ByteArray,
        position: Int,
        onComplete: () -> Unit,
        onError: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            // Create temporary file
            val tempFile = File.createTempFile("tts_audio", ".mp3", context.cacheDir)

            FileOutputStream(tempFile).use { fos ->
                fos.write(audioBytes)
            }

            withContext(Dispatchers.Main) {
                currentMediaPlayer = MediaPlayer().apply {
                    setDataSource(tempFile.absolutePath)
                    setOnPreparedListener { player ->
                        player.start()
                    }
                    setOnCompletionListener { player ->
                        player.release()
                        currentMediaPlayer = null
                        // Clean up temp file
                        tempFile.delete()
                        onComplete()
                    }
                    setOnErrorListener { player, what, extra ->
                        Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                        player.release()
                        currentMediaPlayer = null
                        tempFile.delete()
                        onError("Audio playback error")
                        true
                    }
                    prepareAsync()
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Audio playback error: ${e.message}")
            withContext(Dispatchers.Main) {
                onError(e.message ?: "Audio playback failed")
            }
        }
    }

    fun stopCurrentPlayback() {
        currentMediaPlayer?.let { player ->
            if (player.isPlaying) {
                player.stop()
            }
            player.release()
            currentMediaPlayer = null
        }
    }

    fun isPlaying(): Boolean {
        return currentMediaPlayer?.isPlaying == true
    }
}
