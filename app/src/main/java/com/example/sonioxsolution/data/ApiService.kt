package com.example.sonioxsolution.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class ApiService {


    private val TAG = "ApiService"
    private val client = OkHttpClient()

    private val baseUrl = "http://172.25.0.15:8080"
    private val wsBaseUrl = "ws://172.25.0.15:8080"

    suspend fun getLanguages(): List<Language> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/languages")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Unexpected code $response")

            val body = response.body?.string() ?: ""
            val jsonArray = JSONArray(body)

            val languages = mutableListOf<Language>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                languages.add(
                    Language(
                        code = obj.getString("code"),
                        name = obj.getString("name")
                    )
                )
            }
            languages
        }
    }

    suspend fun startSession(speakerALang: String, speakerBLang: String): SessionResponse =
        withContext(Dispatchers.IO) {
            val formBody = FormBody.Builder()
                .add("speaker_a_lang", speakerALang)
                .add("speaker_b_lang", speakerBLang)
                .build()

            val request = Request.Builder()
                .url("$baseUrl/start-session")
                .post(formBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Unexpected code $response")

                val body = response.body?.string() ?: ""
                val jsonObject = JSONObject(body)

                SessionResponse(
                    session_id = jsonObject.getString("session_id")
                )
            }
        }


    // WebSocket listener interface for audio streaming and receiving real-time responses
    fun createAudioWebSocket(sessionId: String, listener: WebSocketListener): WebSocket {
        val wsUrl = "$wsBaseUrl/ws/$sessionId"
        val request = Request.Builder()
            .url(wsUrl)
            .build()
        Log.d(TAG, "Opening WebSocket to $wsUrl")
        return client.newWebSocket(request, listener)
    }


}