package com.pulse.ai

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pulse.ai.data.ChatRepository
import com.pulse.ai.data.local.ChatEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(private val repository: ChatRepository) : ViewModel() {

    private val _selectedChatId = MutableStateFlow<String?>(null)
    
    private val _temporaryError = MutableStateFlow<String?>(null)

    private val _selectedImage = MutableStateFlow<Uri?>(null)
    val selectedImage: StateFlow<Uri?> = _selectedImage.asStateFlow()
    
    private val _isAiThinking = MutableStateFlow(false)
    
    val uiState: StateFlow<ChatUiState> = combine(
        repository.getAllChats(),
        _selectedChatId.flatMapLatest { id ->
            if (id != null) repository.getMessages(id) else flowOf(emptyList())
        },
        _selectedChatId,
        _temporaryError,
        _isAiThinking
    ) { chats, messages, selectedId, tempError, isThinking ->
        ChatUiState.Success(
            chats = chats,
            selectedChatMessages = messages,
            selectedChatId = selectedId,
            temporaryError = tempError,
            isAiThinking = isThinking
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChatUiState.Loading)

    init {
        // Автовыбор последнего активного чата при запуске (один раз)
        viewModelScope.launch {
            repository.getAllChats().first().let { chats ->
                if (_selectedChatId.value == null && chats.isNotEmpty()) {
                    _selectedChatId.value = chats.first().id
                }
            }
        }
    }

    fun selectChat(chatId: String) {
        _selectedChatId.value = chatId
        _temporaryError.value = null
    }

    fun createNewChat() {
        _temporaryError.value = null
        viewModelScope.launch {
            val id = repository.createNewChat("Новый чат")
            _selectedChatId.value = id
        }
    }

    fun sendMessage(text: String) {
        val chatId = _selectedChatId.value ?: return
        val image = _selectedImage.value
        
        _temporaryError.value = null
        _isAiThinking.value = true
        viewModelScope.launch {
            try {
                repository.sendMessage(chatId, text, if (image != null) listOf(image) else emptyList())
                clearSelectedImage()
            } catch (e: Exception) {
                // Ошибки теперь обрабатываются внутри репозитория и приходят текстом в чат
                android.util.Log.e("ChatViewModel", "Error sending message: ${e.message}")
            } finally {
                _isAiThinking.value = false
            }
        }
    }

    fun clearError() {
        _temporaryError.value = null
    }

    fun deleteChat(chat: ChatEntity) {
        viewModelScope.launch {
            repository.deleteChat(chat)
            if (_selectedChatId.value == chat.id) {
                _selectedChatId.value = null
            }
        }
    }

    fun deleteCurrentChat() {
        val chatId = _selectedChatId.value ?: return
        viewModelScope.launch {
            repository.deleteChatById(chatId)
            _selectedChatId.value = null
        }
    }

    fun onImageSelected(uri: Uri) {
        _selectedImage.value = uri
    }

    fun removeImage() {
        _selectedImage.value = null
    }

    fun clearSelectedImage() {
        _selectedImage.value = null
    }
}
