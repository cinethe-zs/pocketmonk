package app.pocketmonk.repository

import android.content.Context
import app.pocketmonk.model.Conversation
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ConversationRepository(private val context: Context) {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    private fun conversationsDir(): File {
        val dir = File(context.filesDir, "conversations")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun fileFor(id: String): File = File(conversationsDir(), "$id.json")

    suspend fun saveAll(conversations: List<Conversation>) = withContext(Dispatchers.IO) {
        conversations.forEach { save(it) }
    }

    suspend fun loadAll(): List<Conversation> = withContext(Dispatchers.IO) {
        val dir = conversationsDir()
        dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { file ->
                try {
                    gson.fromJson(file.readText(), Conversation::class.java)
                } catch (e: Exception) {
                    null
                }
            }
            ?.sortedByDescending { it.updatedAt }
            ?: emptyList()
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        fileFor(id).delete()
    }

    suspend fun save(conv: Conversation) = withContext(Dispatchers.IO) {
        conv.updatedAt = System.currentTimeMillis()
        fileFor(conv.id).writeText(gson.toJson(conv))
    }
}
