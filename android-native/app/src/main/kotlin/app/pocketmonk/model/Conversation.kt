package app.pocketmonk.model

import java.util.UUID

data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    var title: String,
    val modelPath: String,
    val contextSize: Int = 4096,
    val temperature: Float = 1.0f,
    var systemPrompt: String? = null,
    val tags: MutableList<String> = mutableListOf(),
    val messages: MutableList<Message> = mutableListOf(),
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis()
)
