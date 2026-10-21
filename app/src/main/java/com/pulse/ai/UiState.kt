package com.pulse.ai

import com.pulse.ai.data.local.ChatEntity
import com.pulse.ai.data.local.MessageEntity

sealed interface ChatUiState {
    data object Loading : ChatUiState
    data class Success(
        val chats: List<ChatEntity> = emptyList(),
        val selectedChatMessages: List<MessageEntity> = emptyList(),
        val selectedChatId: String? = null,
        val temporaryError: String? = null,
        val isAiThinking: Boolean = false
    ) : ChatUiState
    data class Error(val message: String) : ChatUiState
}
