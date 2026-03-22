package app.pocketmonk

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.pocketmonk.service.ModelManager
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
                val modelManager = remember { ModelManager(this) }

                NavHost(
                    navController = navController,
                    startDestination = "launch"
                ) {
                    composable("launch") {
                        // Decide start destination based on model availability
                        LaunchedEffect(Unit) {
                            val models = modelManager.listAvailableModels()
                            if (models.isEmpty()) {
                                navController.navigate("setup") {
                                    popUpTo("launch") { inclusive = true }
                                }
                            } else {
                                // Auto-load the first available model
                                val modelPath = models.first().absolutePath
                                viewModel.initModel(modelPath)
                                viewModel.newConversation()
                                navController.navigate("chat") {
                                    popUpTo("launch") { inclusive = true }
                                }
                            }
                        }
                    }

                    composable("setup") {
                        ModelSetupScreen(
                            onModelSelected = { modelPath ->
                                viewModel.initModel(modelPath)
                                viewModel.newConversation()
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
