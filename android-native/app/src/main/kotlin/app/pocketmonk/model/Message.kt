package app.pocketmonk.model

import java.util.UUID

enum class MessageRole { USER, ASSISTANT }
enum class MessageStatus { DONE, STREAMING, ERROR }

data class Message(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    var content: String,
    /** Full text for UI display on stream-processed results. [content] holds a short placeholder for LLM context. */
    var streamDisplay: String? = null,
    var status: MessageStatus = MessageStatus.DONE,
    val createdAt: Long = System.currentTimeMillis(),
    var starred: Boolean = false,
    var isSummary: Boolean = false,
    var isArchived: Boolean = false,
    var isSearchResult: Boolean = false,
    var isSearchLog: Boolean = false,
)
