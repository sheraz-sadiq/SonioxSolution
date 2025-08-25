package com.example.sonioxsolution.presentation.adapter

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.sonioxsolution.R
import com.example.sonioxsolution.data.MessageResponse
import com.example.sonioxsolution.utils.ElevenLabsTTS
import com.example.sonioxsolution.presentation.activity.MainActivity
import com.google.android.material.imageview.ShapeableImageView
import com.airbnb.lottie.LottieAnimationView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class MessagesAdapter(
    private val messages: MutableList<MessageResponse.Message>,
    private val coroutineScope: CoroutineScope,
    private val mainActivity: MainActivity
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_RIGHT = 1
        private const val VIEW_TYPE_LEFT = 2
    }

    private lateinit var ttsService: ElevenLabsTTS
    private var currentPlayingPosition: Int = -1

    // ViewHolder for right messages (speaker == "1")
    inner class RightMessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val actualText: TextView = view.findViewById(R.id.actualText)
        val translatedText: TextView = view.findViewById(R.id.translatedText)
        val btnRightStartSpeech: ShapeableImageView = view.findViewById(R.id.btnStartSpeechRight)
        val btnRightStopSpeech: LottieAnimationView = view.findViewById(R.id.btnStopSpeechRight)
    }

    // ViewHolder for left messages (speaker != "1")
    inner class LeftMessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val actualText: TextView = view.findViewById(R.id.actualText)
        val translatedText: TextView = view.findViewById(R.id.translatedText)
        val btnLeftStartSpeech: ShapeableImageView = view.findViewById(R.id.btnStartSpeechLeft)
        val btnLeftStopSpeech: LottieAnimationView = view.findViewById(R.id.btnStopSpeechLeft)
    }

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].speaker == "1") VIEW_TYPE_RIGHT else VIEW_TYPE_LEFT
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        // Initialize TTS service if not already done
        if (!::ttsService.isInitialized) {
            ttsService = ElevenLabsTTS(parent.context)
        }

        return if (viewType == VIEW_TYPE_RIGHT) {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_right, parent, false)
            RightMessageViewHolder(view)
        } else {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_left, parent, false)
            LeftMessageViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = messages[position]

        when (holder) {
            is RightMessageViewHolder -> {
                holder.actualText.text = message.transcript
                holder.translatedText.text = message.translation ?: ""
                setupTTSButton(holder.btnRightStartSpeech, holder.btnRightStopSpeech, message, position)
            }
            is LeftMessageViewHolder -> {
                holder.actualText.text = message.transcript
                holder.translatedText.text = message.translation ?: ""
                setupTTSButton(holder.btnLeftStartSpeech, holder.btnLeftStopSpeech, message, position)
            }
        }
    }

    private fun setupTTSButton(
        btnStart: ShapeableImageView,
        btnStop: LottieAnimationView,
        message: MessageResponse.Message,
        position: Int
    ) {
        // Get the best available translation text
        val translationText = message.translation ?: ""

        android.util.Log.d("MessagesAdapter", "Position $position - Speaker: ${message.speaker}, Translation: '$translationText'")

        if (translationText.isNotEmpty()) {
            // Show mic button if translation exists
            btnStart.visibility = View.VISIBLE

            // Clear any previous click listener to avoid closure issues
            btnStart.setOnClickListener(null)
            btnStop.setOnClickListener(null)
            updateButtonState(btnStart, btnStop, position)
            // Set new click listener with proper translation capture
            btnStart.setOnClickListener {
                // Get the translation text fresh from the current message to avoid closure issues
                val currentMessage = messages.getOrNull(position)
                val currentTranslation = currentMessage?.translation ?: ""
                val speaker = currentMessage?.speaker ?: "1"
                // Update button state based on current playing position

                android.util.Log.d("MessagesAdapter", "Button clicked for position $position - Speaker: $speaker, Playing translation: '$currentTranslation'")
                playTranslation(btnStart, btnStop, currentTranslation, speaker, position)
            }

            // Set stop button click listener
            btnStop.setOnClickListener {
                android.util.Log.d("MessagesAdapter", "Stop button clicked for position $position")
                stopCurrentPlayback()
            }
        } else {
            // Hide mic button if no translation
            btnStart.visibility = View.GONE
            btnStart.setOnClickListener(null) // Clear click listener
            btnStop.setOnClickListener(null)
        }
    }

    private fun updateButtonState(btnStart: ShapeableImageView, btnStop: LottieAnimationView, position: Int) {
        if (currentPlayingPosition == position) {
            // This position is currently playing or about to play
            btnStart.visibility = View.GONE
            btnStop.visibility = View.VISIBLE
            if (!btnStop.isAnimating) {
                btnStop.playAnimation()
            }
        } else {
            // This position is not playing
            btnStart.visibility = View.VISIBLE
            btnStop.visibility = View.GONE
            btnStop.cancelAnimation()
        }
    }

    fun playLastMessageTTS(){
        if (messages.isNotEmpty()) {
            val lastMessage = messages.last()
            val translationText = lastMessage.translation ?: ""
            val speaker = lastMessage.speaker ?: "1"
            val position = messages.size - 1

            if (translationText.isNotEmpty()) {
                android.util.Log.d("MessagesAdapter", "Playing last message TTS for speaker $speaker at position $position: '$translationText'")
                playTranslation(
                    btnStart = ShapeableImageView(mainActivity),
                    btnStop = LottieAnimationView(mainActivity),
                    translationText = translationText,
                    speaker = speaker,
                    position = position
                )
            } else {
                android.util.Log.d("MessagesAdapter", "No translation available for the last message.")
            }
        } else {
            android.util.Log.d("MessagesAdapter", "No messages to play.")
        }
    }

    private fun playTranslation(
        btnStart: ShapeableImageView,
        btnStop: LottieAnimationView,
        translationText: String,
        speaker: String,
        position: Int
    ) {
        android.util.Log.d("MessagesAdapter", "Starting TTS for speaker $speaker at position $position: '$translationText'")

        // Stop recording before starting TTS
        mainActivity.stopRecording()

        // Stop any currently playing audio and update UI
        stopCurrentPlayback()

        // Immediately update the UI to show this button as playing
        currentPlayingPosition = position
        btnStart.visibility = View.INVISIBLE
        btnStop.visibility = View.VISIBLE
        btnStop.playAnimation()


        coroutineScope.launch {
            ttsService.textToSpeech(
                text = translationText,
                speaker = speaker,
                position = position,
                onStart = {
                    android.util.Log.d("MessagesAdapter", "TTS started for speaker $speaker at position $position: '$translationText'")
                    // UI already updated above, so no need to call notifyDataSetChanged here
                },
                onComplete = {
                    android.util.Log.d("MessagesAdapter", "TTS completed for speaker $speaker at position $position: '$translationText'")
                    currentPlayingPosition = -1
                    // Reset this specific button
                    btnStart.visibility = View.VISIBLE
                    btnStop.visibility = View.GONE
                    btnStop.cancelAnimation()

                    if (!ttsService.isPlaying()) {
                        stopAllTTS()
                    }
                },
                onError = { error ->
                    android.util.Log.e("MessagesAdapter", "TTS error for speaker $speaker at position $position '$translationText': $error")
                    currentPlayingPosition = -1
                    // Reset this specific button
                    btnStart.visibility = View.VISIBLE
                    btnStop.visibility = View.GONE
                    btnStop.cancelAnimation()
                }
            )
        }
    }


    private fun stopCurrentPlayback() {
        if (::ttsService.isInitialized) {
            ttsService.stopCurrentPlayback()
        }
        currentPlayingPosition = -1
        notifyDataSetChanged()

    }

    override fun getItemCount(): Int = messages.size

    fun addMessages(newMessages: List<MessageResponse.Message>) {
        messages.addAll(newMessages)
        notifyItemRangeInserted(messages.size - newMessages.size, newMessages.size)
    }

    fun updateMessages(newMessages: List<MessageResponse.Message>) {
        // Stop any current TTS playback when updating messages
        if (::ttsService.isInitialized) {
            ttsService.stopCurrentPlayback()
        }

        messages.clear()
        messages.addAll(newMessages)
        notifyDataSetChanged()
    }

    fun stopAllTTS() {
        if (::ttsService.isInitialized) {
            ttsService.stopCurrentPlayback()
        }

        currentPlayingPosition = -1
        notifyDataSetChanged()
    }
}

