package app.pocketmonk

import android.app.Application
import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class PocketMonkApp : Application() {

    override fun onCreate() {
        super.onCreate()

        PDFBoxResourceLoader.init(applicationContext)

        val prefs = getSharedPreferences("crash_log", Context.MODE_PRIVATE)

        // Detect native crash during model loading (SIGABRT from MediaPipe).
        // LlmService sets model_loading=true before createFromOptions and clears it on success.
        // If the flag is still set here, the process died natively during that call.
        if (prefs.getBoolean("model_loading", false)) {
            val failedPath = prefs.getString("model_loading_path", "unknown") ?: "unknown"
            val filename = failedPath.substringAfterLast("/")
            prefs.edit()
                .remove("model_loading")
                .remove("model_loading_path")
                .putString(
                    "last_crash",
                    "Native crash (SIGABRT) while loading model:\n$filename\n\n" +
                    "Possible causes:\n" +
                    "• Not enough RAM to load the model\n" +
                    "• GPU/hardware incompatibility with MediaPipe\n" +
                    "• Corrupted .task file\n\n" +
                    "Try deleting and re-downloading, or use a smaller model."
                )
                .commit()
            // Clear the saved model path so next launch goes to setup, not crash loop.
            getSharedPreferences("pocketmonk_prefs", Context.MODE_PRIVATE)
                .edit().remove("active_model_path").commit()
        }

        // Save any unhandled JVM exceptions to prefs so the UI can show them.
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val msg = buildString {
                appendLine(throwable.toString())
                throwable.stackTrace.take(15).forEach { appendLine("  at $it") }
            }
            prefs.edit().putString("last_crash", msg).commit()
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        fun getLastCrash(context: Context): String? {
            val prefs = context.getSharedPreferences("crash_log", Context.MODE_PRIVATE)
            return prefs.getString("last_crash", null)
        }

        fun clearLastCrash(context: Context) {
            context.getSharedPreferences("crash_log", Context.MODE_PRIVATE)
                .edit().remove("last_crash").apply()
        }
    }
}
