package com.example.sonioxsolution.presentation.activity

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.sonioxsolution.databinding.ActivityMainBinding
import com.example.sonioxsolution.data.ApiClient
import com.example.sonioxsolution.data.Language
import com.example.sonioxsolution.data.SessionResponse
import com.example.sonioxsolution.data.MessageResponse
import com.example.sonioxsolution.presentation.adapter.MessagesAdapter
import com.example.sonioxsolution.utils.AudioRecorder
import com.example.sonioxsolution.utils.PermissionHelper
import com.example.sonioxsolution.utils.WebSocketClient
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"

    private lateinit var binding: ActivityMainBinding
    private val apiService = ApiClient.apiService

    // Data class to track message order
    private data class MessageWithOrder(
        val message: MessageResponse.Message,
        val order: Int,
        val isFinal: Boolean
    )

    // THIS IS THE GLOBAL MESSAGE STORE - persists across stop/start recording
    private val allMessages = mutableListOf<MessageWithOrder>()
    private var messageOrderCounter = 0

    // Current non-final messages per speaker with their order
    private var currentNonFinalMessageSpeaker1: MessageWithOrder? = null
    private var currentNonFinalMessageSpeaker2: MessageWithOrder? = null

    // Track cumulative final text for each speaker
    private var speaker1FinalTranscript = ""
    private var speaker1FinalTranslation = ""
    private var speaker2FinalTranscript = ""
    private var speaker2FinalTranslation = ""


    private var languagesList = listOf<Language>()
    private var languageNames = mutableListOf<String>()

    private lateinit var sourceLanguage: Language
    private lateinit var targetLanguage: Language
    private var sourceSelected = false
    private var targetSelected = false

    private lateinit var session: SessionResponse
    private var currentSessionId: String? = null

    private var audioRecorder: AudioRecorder? = null
    private var webSocketClient: WebSocketClient? = null

    private lateinit var messagesAdapter: MessagesAdapter

    companion object {
        private const val REQUEST_RECORD_AUDIO_PERMISSION = 1234
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Handle system bars for edge-to-edge UI
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Request necessary permissions if not granted
        if (!PermissionHelper.hasPermissions(this)) {
            PermissionHelper.requestPermissions(this)
        }

        // Setup swipe to refresh listener
        binding.swipeRefreshLayout.setOnRefreshListener {
            // Called on swipe refresh gesture
            refreshPage()
        }

        // Fetch available languages from API and populate spinners
        fetchLanguages()

        binding.sourceLanguageSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                    if (position == 0) {
                        // User hasn't selected a real language yet
                        sourceSelected = false
                        sourceLanguage = Language("", "")  // Or null, if you want to allow that
                        Log.d(TAG, "Source language not selected")
                    } else {
                        sourceSelected = true
                        sourceLanguage = languagesList[position - 1] // subtract 1 because of dummy item
                        Log.d(TAG, "Selected source language: ${sourceLanguage.name} (${sourceLanguage.code})")
                    }

                    clearMessages()
                }
                override fun onNothingSelected(parent: AdapterView<*>) {}
            }

        binding.targetLanguageSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                    if (position == 0) {
                        targetSelected = false
                        targetLanguage = Language("", "")
                        Log.d(TAG, "Target language not selected")
                    } else {
                        targetSelected = true
                        targetLanguage = languagesList[position - 1]
                        Log.d(TAG, "Selected target language: ${targetLanguage.name} (${targetLanguage.code})")
                    }

                    clearMessages()
                }
                override fun onNothingSelected(parent: AdapterView<*>) {}
            }




        // Initialize the messages adapter with empty list
        messagesAdapter = MessagesAdapter(mutableListOf(), lifecycleScope, this)


        // Setup RecyclerView for showing messages
        binding.recyclerView.apply {
            adapter = messagesAdapter
            layoutManager = LinearLayoutManager(this@MainActivity).apply {
                stackFromEnd = false    // Start at top of list
                reverseLayout = false   // Normal top-to-bottom order
            }
        }

        // Microphone button click starts recording if permission granted
        binding.btnStartRecording.setOnClickListener {
            if (!sourceSelected || !targetSelected) {
                Toast.makeText(this, "Please select both source and target languages", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (sourceLanguage.code == targetLanguage.code) {
                Toast.makeText(this, "Same languages not allowed. Please select different languages.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Mark last message as final if needed
            if (allMessages.isNotEmpty()) {
                val lastIndex = allMessages.size - 1
                val lastMessageWithOrder = allMessages[lastIndex]
                if (!lastMessageWithOrder.isFinal) {
                    allMessages[lastIndex] = lastMessageWithOrder.copy(isFinal = true)
                }
            }

            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startSession(sourceLanguage.code, targetLanguage.code)
            } else {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO_PERMISSION)
            }
        }



        // Stop recording button click stops recording
        binding.btnStopRecording.setOnClickListener {
            stopRecording()
        }
    }

    /**
     * Fetch languages from API and populate source and target language spinners.
     * Hide the selection UI while loading and show once loaded.
     */
    private fun fetchLanguages() {
        binding.LanguageSelectionLayout.visibility = View.GONE // Hide while loading

        lifecycleScope.launchWhenCreated {
            try {
                languagesList = apiService.getLanguages()
                languageNames.clear()

                // Add a dummy prompt item at first position
                languageNames.add("Select language")
                languageNames.addAll(languagesList.map { it.name })

                val adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, languageNames)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

                binding.sourceLanguageSpinner.adapter = adapter
                binding.targetLanguageSpinner.adapter = adapter

                // Set spinner selection to the dummy prompt at index 0
                binding.sourceLanguageSpinner.setSelection(0, false)
                binding.targetLanguageSpinner.setSelection(0, false)

                // Reset selected language flags
                sourceSelected = false
                targetSelected = false

                binding.LanguageSelectionLayout.visibility = View.VISIBLE // Show after loading
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching languages: ${e.message}")
                Toast.makeText(this@MainActivity, "Failed to load languages", Toast.LENGTH_SHORT).show()
                binding.LanguageSelectionLayout.visibility = View.GONE // Hide if error occurs
            }
        }
    }

    /**
     * Start session with given source and target language codes.
     * Called when both languages are selected.
     */
    private fun startSession(speakerALang: String, speakerBLang: String) {
        lifecycleScope.launchWhenCreated @RequiresPermission(Manifest.permission.RECORD_AUDIO) {
            try {
                session = apiService.startSession(speakerALang, speakerBLang)
                onSessionStarted(session.session_id)

                // Check permission before starting recording
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    startRecording()
                } else {
                    // Request permission if not granted
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO_PERMISSION)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting session: ${e.message}")
            }
        }
    }

    /**
     * Called when a session has been successfully started.
     * Sets up the WebSocket client for receiving real-time messages.
     */
    fun onSessionStarted(sessionId: String) {
        currentSessionId = sessionId
        Log.d(TAG, "Session started with ID: $sessionId")

        webSocketClient = WebSocketClient(
            sessionId,
            apiService,
            lifecycleScope,
            onMessagesReceived = { newMessages ->
                runOnUiThread {
                    val mergedMessages = mergeEmptyTranslationMessages(newMessages.toMutableList())
                    messagesAdapter.updateMessages(mergedMessages)
                    val layoutManager = binding.recyclerView.layoutManager as LinearLayoutManager
                    val lastVisiblePosition = layoutManager.findLastCompletelyVisibleItemPosition()

                    if (lastVisiblePosition >= messagesAdapter.itemCount - 2) {
                        binding.recyclerView.scrollToPosition(messagesAdapter.itemCount - 1)
                    }
                }
            },
            onError = { error ->
                Log.e(TAG, "WebSocket error: $error")
            },
            onClosed = {
                runOnUiThread {
                    stopRecording()
                }
            },
            onErrorDetected = {
                // Handle error detection: stop recording and play last message TTS
                runOnUiThread {
                    Log.d(TAG, "Error detected from WebSocket - stopping recording and playing last message TTS")

                    // 1. Stop recording immediately
                    stopRecording()

                    // 2. Close the WebSocket to clean up resources
                    webSocketClient?.close()


                    // Wait a short moment for UI to update, then play TTS
                    binding.recyclerView.postDelayed({
                        messagesAdapter.playLastMessageTTS()
                    }, 500) // 500ms delay to ensure recording has fully stopped
                }
            },
            // Pass message processing callback to WebSocketClient
            messageProcessor = { newMessage -> processMessage(newMessage) }
        )

    }

    /**
     * Start audio recording.
     * Disable refresh and language spinners to prevent conflicts during recording.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startRecording() {

        // Stop any ongoing TTS and reset buttons
        messagesAdapter.stopAllTTS()
        // Update UI to recording state
        binding.btnStartRecording.visibility = View.GONE
        binding.btnStopRecording.visibility = View.VISIBLE
        binding.btnStopRecording.playAnimation()

        // Disable refresh and language selection during recording to prevent conflicts
        binding.swipeRefreshLayout.isEnabled = false
        binding.sourceLanguageSpinner.isEnabled = false
        binding.targetLanguageSpinner.isEnabled = false

        audioRecorder = AudioRecorder()
        audioRecorder?.startRecording(lifecycleScope)

        // Start sending audio chunks to WebSocket server
        // WebSocketClient will automatically show existing messages from allMessages
        webSocketClient?.start(audioRecorder!!.audioChannel)
        
    }

    /**
     * Stop audio recording.
     * Re-enable refresh and language spinners.
     * Keep WebSocketClient alive to preserve allMessages
     */
    fun stopRecording() {
        // Update UI to non-recording state
        binding.btnStopRecording.cancelAnimation()
        binding.btnStopRecording.visibility = View.GONE
        binding.btnStartRecording.visibility = View.VISIBLE

        // Enable refresh and language selection again
        binding.swipeRefreshLayout.isEnabled = true
        binding.sourceLanguageSpinner.isEnabled = true
        binding.targetLanguageSpinner.isEnabled = true

        // Stop audio recording
        audioRecorder?.stopRecording()
        webSocketClient?.close()

    }

    private fun clearMessages() {
        // Clear the global message store in MainActivity
        allMessages.clear()
        currentNonFinalMessageSpeaker1 = null
        currentNonFinalMessageSpeaker2 = null
        speaker1FinalTranscript = ""
        speaker1FinalTranslation = ""
        speaker2FinalTranscript = ""
        speaker2FinalTranslation = ""
        messageOrderCounter = 0

        // Clear the UI
        messagesAdapter.updateMessages(mutableListOf())
        Log.d(TAG, "Messages cleared from MainActivity")
    }

    /**
     * Process incoming messages and manage the global message store
     * This function was moved from WebSocketClient to MainActivity
     */
    private fun processMessage(newMessage: MessageResponse.Message): List<MessageResponse.Message> {
        if (newMessage.is_final && newMessage.transcript_non_final.isNullOrEmpty() && newMessage.translation_non_final.isNullOrEmpty()) {
            // Extract new text that hasn't been shown before
            val newTranscript = extractNewText(newMessage.transcript, newMessage.speaker, isTranscript = true)
            val newTranslation = extractNewText(newMessage.translation ?: "", newMessage.speaker, isTranscript = false)




            // Find the existing non-final message for this speaker
            val existingNonFinalMessage = if (newMessage.speaker == "1") {
                currentNonFinalMessageSpeaker1
            } else {
                currentNonFinalMessageSpeaker2
            }

            if (existingNonFinalMessage != null) {
                // Remove ONLY the specific non-final message for this speaker
                allMessages.removeIf { it.order == existingNonFinalMessage.order && !it.isFinal }

                // Only add final message if there's new content
                if (newTranscript.isNotEmpty() || newTranslation.isNotEmpty()) {
                    val finalMessage = newMessage.copy(
                        transcript = newTranscript,
                        translation = newTranslation
                    )
                    allMessages.add(MessageWithOrder(finalMessage, existingNonFinalMessage.order, true))
                }
            } else {
                // No existing non-final message, just add final if there's new content
                if (newTranscript.isNotEmpty() || newTranslation.isNotEmpty()) {
                    val finalMessage = newMessage.copy(
                        transcript = newTranscript,
                        translation = newTranslation
                    )
                    allMessages.add(MessageWithOrder(finalMessage, messageOrderCounter++, true))
                }
            }

            // Update cumulative final text
            updateCumulativeFinalText(newMessage.transcript, newMessage.translation ?: "", newMessage.speaker)

            // Clear the non-final message for this specific speaker
            if (newMessage.speaker == "1") {
                currentNonFinalMessageSpeaker1 = null
            } else {
                currentNonFinalMessageSpeaker2 = null
            }
        } else {
            // For non-final messages, show the part that's beyond the final text
            val transcriptBeyondFinal = getTextBeyondFinal(newMessage.transcript, newMessage.speaker, isTranscript = true)
            val translationBeyondFinal = getTextBeyondFinal(newMessage.translation ?: "", newMessage.speaker, isTranscript = false)

            val nonFinalMessage = newMessage.copy(
                transcript = transcriptBeyondFinal,
                translation = translationBeyondFinal
            )

            // Update or create the non-final message for the specific speaker
            val existingNonFinalMessage = if (newMessage.speaker == "1") {
                currentNonFinalMessageSpeaker1
            } else {
                currentNonFinalMessageSpeaker2
            }

            if (existingNonFinalMessage != null) {
                // Update existing non-final message - remove ONLY the specific non-final message
                allMessages.removeIf { it.order == existingNonFinalMessage.order && !it.isFinal }
                if (transcriptBeyondFinal.isNotEmpty() || translationBeyondFinal.isNotEmpty()) {
                    val updatedMessage = MessageWithOrder(nonFinalMessage, existingNonFinalMessage.order, false)
                    allMessages.add(updatedMessage)

                    if (newMessage.speaker == "1") {
                        currentNonFinalMessageSpeaker1 = updatedMessage
                    } else {
                        currentNonFinalMessageSpeaker2 = updatedMessage
                    }
                } else {
                    // Clear the non-final message if no content
                    if (newMessage.speaker == "1") {
                        currentNonFinalMessageSpeaker1 = null
                    } else {
                        currentNonFinalMessageSpeaker2 = null
                    }
                }
            } else {
                // Create new non-final message (speaker changed or first message from this speaker)
                if (transcriptBeyondFinal.isNotEmpty() || translationBeyondFinal.isNotEmpty()) {
                    val newMessageWithOrder = MessageWithOrder(nonFinalMessage, messageOrderCounter++, false)
                    allMessages.add(newMessageWithOrder)

                    if (newMessage.speaker == "1") {
                        currentNonFinalMessageSpeaker1 = newMessageWithOrder
                    } else {
                        currentNonFinalMessageSpeaker2 = newMessageWithOrder
                    }
                }
            }
        }

        // Sort messages by order and extract just the Message objects
        return allMessages
            .sortedBy { it.order }
            .map { it.message }
    }

    /**
     * Extract only the new text that hasn't been shown in previous final messages
     */
    private fun extractNewText(fullText: String, speaker: String, isTranscript: Boolean): String {
        val previousFinalText = if (speaker == "1") {
            if (isTranscript) speaker1FinalTranscript else speaker1FinalTranslation
        } else {
            if (isTranscript) speaker2FinalTranscript else speaker2FinalTranslation
        }

        return if (fullText.startsWith(previousFinalText)) {
            fullText.substring(previousFinalText.length).trim()
        } else {
            fullText
        }
    }

    /**
     * Get text that's beyond the current final text (for non-final messages)
     */
    private fun getTextBeyondFinal(fullText: String, speaker: String, isTranscript: Boolean): String {
        val finalText = if (speaker == "1") {
            if (isTranscript) speaker1FinalTranscript else speaker1FinalTranslation
        } else {
            if (isTranscript) speaker2FinalTranscript else speaker2FinalTranslation
        }

        return if (fullText.startsWith(finalText)) {
            fullText.substring(finalText.length).trim()
        } else {
            fullText
        }
    }

    /**
     * Update the cumulative final text for the speaker
     */
    private fun updateCumulativeFinalText(transcript: String, translation: String, speaker: String) {
        if (speaker == "1") {
            speaker1FinalTranscript = transcript
            speaker1FinalTranslation = translation
        } else {
            speaker2FinalTranscript = transcript
            speaker2FinalTranslation = translation
        }
    }


    fun mergeEmptyTranslationMessages(messages: MutableList<MessageResponse.Message>): MutableList<MessageResponse.Message> {
        val mergedMessages = mutableListOf<MessageResponse.Message>()
        var i = 0
        while (i < messages.size) {
            val current = messages[i]
            if ((current.translation.isNullOrEmpty()) && i < messages.size - 1) {
                val next = messages[i + 1]
                // Merge transcript and translation from next
                val merged = next.copy(
                    transcript = "${current.transcript}, ${next.transcript}".trim(),
                    translation = next.translation
                )
                mergedMessages.add(merged)
                i += 2 // Skip next, as it's merged
            } else {
                mergedMessages.add(current)
                i++
            }
        }

        return mergedMessages
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Microphone permission granted.")
                startSession(sourceLanguage.code, targetLanguage.code)
            } else {
                Log.e(TAG, "Microphone permission denied.")
            }
        }
    }

    /**
     * Refresh logic triggered by swipe refresh gesture.
     * Clears messages and reloads languages.
     */
    private fun refreshPage() {
        lifecycleScope.launch {
            try {
                // Clear messages on refresh to empty RecyclerView
                clearMessages()

                // Reload languages from API (will also update spinners)
                fetchLanguages()

                // Add other refresh logic here if needed

            } finally {
                // Stop swipe refresh animation once refresh completes
                binding.swipeRefreshLayout.isRefreshing = false
            }
        }
    }
}
