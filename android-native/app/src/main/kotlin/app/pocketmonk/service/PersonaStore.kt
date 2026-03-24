package app.pocketmonk.service

import android.content.Context
import app.pocketmonk.model.Persona
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class PersonaStore(context: Context) {
    private val prefs = context.getSharedPreferences("pocketmonk_personas", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun load(): List<Persona> {
        val json = prefs.getString("personas", null) ?: return emptyList()
        val type = object : TypeToken<List<Persona>>() {}.type
        return runCatching { gson.fromJson<List<Persona>>(json, type) ?: emptyList() }
            .getOrDefault(emptyList())
    }

    fun save(personas: List<Persona>) {
        prefs.edit().putString("personas", gson.toJson(personas)).apply()
    }
}
