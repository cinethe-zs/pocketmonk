package app.pocketmonk

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.pocketmonk.ui.ChatScreen
import app.pocketmonk.ui.ModelSetupScreen
import app.pocketmonk.ui.theme.PocketMonkTheme
import app.pocketmonk.viewmodel.ChatViewModel

class MainActivity : ComponentActivity() {

    // Shared file URI — set on fresh launch or new intent, consumed by ChatScreen.
    // Backed by Compose state so ChatScreen recomposes automatically when it changes.
    var pendingShareUri: Uri? by mutableStateOf(null)
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingShareUri = intent.shareStreamUri()
        setContent {
            PocketMonkTheme {
                val navController = rememberNavController()
                val viewModel: ChatViewModel = viewModel()

                NavHost(navController = navController, startDestination = "launch") {

                    composable("launch") {
                        val context = LocalContext.current
                        LaunchedEffect(Unit) {
                            if (PocketMonkApp.getLastCrash(context) != null) {
                                navController.navigate("setup") {
                                    popUpTo("launch") { inclusive = true }
                                }
                                return@LaunchedEffect
                            }

                            val savedPath = viewModel.modelManager.getActiveModelPath()
                            val localFiles = viewModel.modelManager.listLocalFiles()
                            when {
                                savedPath != null && java.io.File(savedPath).exists() -> {
                                    viewModel.initModel(savedPath)
                                    navController.navigate("chat") {
                                        popUpTo("launch") { inclusive = true }
                                    }
                                }
                                localFiles.isNotEmpty() -> {
                                    val path = localFiles.first().absolutePath
                                    viewModel.modelManager.setActiveModelPath(path)
                                    viewModel.initModel(path)
                                    navController.navigate("chat") {
                                        popUpTo("launch") { inclusive = true }
                                    }
                                }
                                else -> {
                                    navController.navigate("setup") {
                                        popUpTo("launch") { inclusive = true }
                                    }
                                }
                            }
                        }
                    }

                    composable("setup") {
                        ModelSetupScreen(
                            viewModel = viewModel,
                            onModelReady = { modelPath ->
                                viewModel.initModel(modelPath)
                                if (!navController.popBackStack("chat", inclusive = false)) {
                                    navController.navigate("chat") {
                                        popUpTo("setup") { inclusive = true }
                                    }
                                }
                            }
                        )
                    }

                    composable("chat") {
                        ChatScreen(
                            viewModel = viewModel,
                            onNavigateToDownload = { navController.navigate("setup") }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.shareStreamUri()?.let { pendingShareUri = it }
    }

    fun consumePendingShareUri() {
        pendingShareUri = null
    }
}

/** Returns the EXTRA_STREAM Uri if this is an ACTION_SEND intent, otherwise null. */
@Suppress("DEPRECATION")
private fun Intent.shareStreamUri(): Uri? =
    if (action == Intent.ACTION_SEND) getParcelableExtra<Uri>(Intent.EXTRA_STREAM) else null
