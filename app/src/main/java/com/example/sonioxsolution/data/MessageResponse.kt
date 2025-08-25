
package com.example.sonioxsolution.data



data class MessageResponse(
    val messages: List<Message>
) {
    data class Message(
        val speaker: String,
        val language: String,
        val transcript: String,
        val translation: String? = null,
        val transcript_final: String? = null,
        val translation_final: String? = null,
        val transcript_non_final: String? = null,
        val translation_non_final: String? = null,
        val is_final: Boolean = false

    )
}

