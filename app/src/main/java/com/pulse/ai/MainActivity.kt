package com.pulse.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.pulse.ai.ui.chat.ChatScreen
import com.pulse.ai.ui.about.AboutScreen
import com.pulse.ai.ui.theme.PulseAITheme
import androidx.compose.runtime.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

import com.pulse.ai.service.PulseAiService
import android.content.Intent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        
        // Запуск фонового сервиса
        // startForegroundService(Intent(this, PulseAiService::class.java))
        
        val repository = (application as PulseAiApplication).repository

        setContent {
            PulseAITheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val chatViewModel: ChatViewModel = viewModel(
                        factory = object : ViewModelProvider.Factory {
                            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                                return ChatViewModel(repository) as T
                            }
                        }
                    )
                    
                    var currentScreen by remember { mutableStateOf("chat") }

                    when (currentScreen) {
                        "chat" -> ChatScreen(
                            viewModel = chatViewModel,
                            onAboutClick = { currentScreen = "about" }
                        )
                        "about" -> AboutScreen(
                            onBackClick = { currentScreen = "chat" }
                        )
                    }
                }
            }
        }
    }
}
