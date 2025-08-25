package com.example.sonioxsolution.utils

import android.util.Log
import com.example.sonioxsolution.data.ApiClient
import com.example.sonioxsolution.data.ApiService
import com.example.sonioxsolution.data.MessageResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.ReceiveChannel
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject

class WebSocketClient(
    private val sessionId: String,
    private val apiService: ApiService = ApiClient.apiService,
    private val scope: CoroutineScope,
    private val onMessagesReceived: (List<MessageResponse.Message>) -> Unit,
    private val onError: (String) -> Unit,
    private val onClosed: () -> Unit,
    private val onErrorDetected: () -> Unit,
    private val messageProcessor: (MessageResponse.Message) -> List<MessageResponse.Message>
) {

    private val TAG = "WebSocketClient"
    private lateinit var webSocket: WebSocket
    private var isWebSocketOpen = false

    fun start(audioChunks: ReceiveChannel<ByteArray>) {
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket opened")
                isWebSocketOpen = true

                // Send audio chunks as they arrive
                scope.launch(Dispatchers.IO) {
                    for (chunk in audioChunks) {
                        if (!isWebSocketOpen) break
                        webSocket.send(ByteString.of(*chunk))
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received message: $text")
                try {
                    val json = JSONObject(text)

                    // Check for error messages first
                    if (json.has("error")) {
                        val errorMessage = json.optString("error", "Unknown error")
                        Log.e(TAG, "Error message received from WebSocket: $errorMessage")

                        // Trigger error detection callback
                        onErrorDetected()

                        // Also call the regular error callback
                        onError(errorMessage)

                        return
                    }

                    // Check for timeout or other error indicators
                    if (json.has("status") && json.getString("status").lowercase().contains("error")) {
                        val statusMessage = json.optString("status", "Status error")
                        Log.e(TAG, "Error status received from WebSocket: $statusMessage")

                        onErrorDetected()
                        onError(statusMessage)
                        return
                    }

                    if (json.has("messages")) {
                        val messagesJson = json.getJSONArray("messages")

                        for (i in 0 until messagesJson.length()) {
                            val msgObj = messagesJson.getJSONObject(i)
                            val speaker = msgObj.getString("speaker")
                            val language = msgObj.getString("language")
                            val transcript = msgObj.getString("transcript")
                            val translation = msgObj.optString("translation", "")
                            val transcriptFinal = msgObj.optString("transcript_final", "")
                            val translationFinal = msgObj.optString("translation_final", "")
                            val transcriptNonFinal = msgObj.optString("transcript_non_final", "")
                            val translationNonFinal = msgObj.optString("translation_non_final", "")
                            val isFinal = msgObj.optBoolean("is_final", false)

                            val newMessage = MessageResponse.Message(
                                speaker = speaker,
                                language = language,
                                transcript = transcript,
                                translation = translation,
                                transcript_final = transcriptFinal,
                                translation_final = translationFinal,
                                transcript_non_final = transcriptNonFinal,
                                translation_non_final = translationNonFinal,
                                is_final = isFinal
                            )

                            // Process the message using the provided messageProcessor callback
                            val processedMessages = messageProcessor(newMessage)

                            // Send the complete list to the UI
                            onMessagesReceived(processedMessages)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "JSON parse error: ${e.message}")
                    e.printStackTrace()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isWebSocketOpen = false
                Log.e(TAG, "WebSocket failed: ${t.message}")

                onError(t.message ?: "Unknown WebSocket failure")
                onClosed() // <-- Notify activity to stop recording
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                isWebSocketOpen = false
                webSocket.close(code, reason)
                Log.d(TAG, "WebSocket closing: $code / $reason")

                onClosed() // <-- Notify activity to stop recording
            }

        }

        webSocket = apiService.createAudioWebSocket(sessionId, listener)
    }



    fun close() {
        if (isWebSocketOpen) {
            webSocket.close(1000, "Closing normally")
            isWebSocketOpen = false
        }
    }
}
