package app.pocketmonk

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
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
                        LaunchedEffect(Unit) {
                            // Check for a previously selected model or any downloaded file
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
                                navController.navigate("chat") {
                                    popUpTo("setup") { inclusive = true }
                                }
                            }
                        )
                    }

                    composable("chat") {
                        ChatScreen(viewModel = viewModel)
                    }
                }
            }
        }
    }
}
