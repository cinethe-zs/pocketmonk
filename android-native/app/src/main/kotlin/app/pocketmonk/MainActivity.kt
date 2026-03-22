package app.pocketmonk

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PocketMonkTheme {
                val navController = rememberNavController()
                val viewModel: ChatViewModel = viewModel()

                NavHost(navController = navController, startDestination = "launch") {

                    composable("launch") {
                        val context = LocalContext.current
                        LaunchedEffect(Unit) {
                            // If a previous crash was detected, go to setup so the user
                            // sees the crash banner and can choose what to do next.
                            if (PocketMonkApp.getLastCrash(context) != null) {
                                navController.navigate("setup") {
                                    popUpTo("launch") { inclusive = true }
                                }
                                return@LaunchedEffect
                            }

                            // Auto-load a known-good model path, or the first local file found
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
                                // If "chat" is already in the back stack (user came from chat),
                                // pop back to it. Otherwise navigate fresh to chat.
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
}
