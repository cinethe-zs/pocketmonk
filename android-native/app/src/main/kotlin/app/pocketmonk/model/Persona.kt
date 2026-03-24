package app.pocketmonk.model

import java.util.UUID

data class Persona(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val systemPrompt: String
)
