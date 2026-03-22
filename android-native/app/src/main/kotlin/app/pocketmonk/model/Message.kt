package app.pocketmonk.model

import java.util.UUID

enum class MessageRole { USER, ASSISTANT }
enum class MessageStatus { DONE, STREAMING, ERROR }

data class Message(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    var content: String,
    var status: MessageStatus = MessageStatus.DONE,
    val createdAt: Long = System.currentTimeMillis(),
    var starred: Boolean = false,
    var isSummary: Boolean = false
)
