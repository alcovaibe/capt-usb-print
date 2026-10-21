package com.pulse.ai.data

import com.pulse.ai.BuildConfig
import com.pulse.ai.data.local.ChatDao
import com.pulse.ai.data.local.ChatEntity
import com.pulse.ai.data.local.MessageEntity
import com.pulse.ai.data.remote.Content
import com.pulse.ai.data.remote.GeminiApi
import com.pulse.ai.data.remote.GeminiRequest
import com.pulse.ai.data.remote.Part
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import java.util.*
import android.content.Context
import androidx.core.app.NotificationCompat
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.BitmapFactory
import com.pulse.ai.MainActivity
import com.pulse.ai.R
import com.pulse.ai.service.PulseAiService

class ChatRepository(
    private val context: Context,
    private val chatDao: ChatDao,
    private val geminiApi: GeminiApi
) {
    fun getAllChats(): Flow<List<ChatEntity>> = chatDao.getAllChats()

    fun getMessages(chatId: String): Flow<List<MessageEntity>> = chatDao.getMessagesForChat(chatId)

    suspend fun createNewChat(title: String): String {
        val id = UUID.randomUUID().toString()
        chatDao.insertChat(ChatEntity(id, title, System.currentTimeMillis()))
        return id
    }

    suspend fun deleteChat(chat: ChatEntity) {
        chatDao.deleteMessagesForChat(chat.id)
        chatDao.deleteChat(chat)
    }

    suspend fun deleteChatById(chatId: String) {
        chatDao.deleteMessagesForChat(chatId)
        chatDao.deleteChatById(chatId)
    }

    suspend fun sendMessage(chatId: String, text: String, imageUris: List<android.net.Uri> = emptyList()) {
        android.util.Log.i("ChatRepository", "sendMessage called for chat: $chatId with ${imageUris.size} images")
        
        if (BuildConfig.GEMINI_API_KEY.isEmpty()) {
            throw Exception(context.getString(R.string.error_no_api_key))
        }

        val userMessage = MessageEntity(
            chatId = chatId,
            content = text,
            isFromUser = true,
            timestamp = System.currentTimeMillis(),
            imageUris = if (imageUris.isEmpty()) null else imageUris.joinToString(",") { it.toString() }
        )
        chatDao.insertMessage(userMessage)
        
        // Всегда обновляем время чата, чтобы он поднялся в начало списка
        chatDao.getChatById(chatId)?.let { chat ->
            chatDao.insertChat(chat.copy(lastUpdateTime = System.currentTimeMillis()))
        }

        try {
            val history = chatDao.getLastMessages(chatId).reversed()
            val contents = history.map { 
                Content(role = if (it.isFromUser) "user" else "model", parts = listOf(Part(it.content)))
            }
            
            android.util.Log.i("ChatRepository", "Sending request to Gemini v1...")
            val response = geminiApi.generateContent(
                apiKey = BuildConfig.GEMINI_API_KEY,
                request = GeminiRequest(contents = contents)
            )
            
            val aiText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            android.util.Log.i("ChatRepository", "AI Response received: ${aiText?.take(20)}...")

            if (aiText != null) {
                val aiMessage = MessageEntity(
                    chatId = chatId,
                    content = aiText,
                    isFromUser = false,
                    timestamp = System.currentTimeMillis()
                )
                chatDao.insertMessage(aiMessage)
                
                // Сначала просто обновим время чата, чтобы он был сверху
                val currentChat = chatDao.getChatById(chatId)
                if (currentChat != null) {
                    chatDao.insertChat(currentChat.copy(lastUpdateTime = System.currentTimeMillis()))
                    
                    // Если это новый чат, попробуем переименовать его по контексту
                    if (currentChat.title == "Новый чат") {
                        generateAndSetChatTitle(chatId, text)
                    }
                }
            } else {
                android.util.Log.w("ChatRepository", "AI returned empty text.")
                throw Exception(context.getString(R.string.error_empty_ai_response))
            }
        } catch (e: retrofit2.HttpException) {
            val errorBody = e.response()?.errorBody()?.string()
            android.util.Log.e("ChatRepository", "HTTP ERROR ${e.code()}: $errorBody")
            
            val errorMessage = when(e.code()) {
                403 -> context.getString(R.string.error_403)
                429 -> context.getString(R.string.error_429)
                400 -> context.getString(R.string.error_400)
                else -> context.getString(R.string.error_generic, e.code())
            }
            
            // Вставляем ошибку прямо в чат как сообщение от ИИ
            chatDao.insertMessage(MessageEntity(
                chatId = chatId,
                content = "Ошибка: $errorMessage",
                isFromUser = false,
                timestamp = System.currentTimeMillis()
            ))
        } catch (e: Exception) {
            android.util.Log.e("ChatRepository", "CRITICAL ERROR: ${e.message}", e)
            chatDao.insertMessage(MessageEntity(
                chatId = chatId,
                content = "Произошла ошибка: ${e.localizedMessage ?: "неизвестная ошибка"}",
                isFromUser = false,
                timestamp = System.currentTimeMillis()
            ))
        }
    }

    private suspend fun generateAndSetChatTitle(chatId: String, firstMessage: String) {
        try {
            val prompt = "Сгенерируй очень короткое (2-4 слова) название для чата, который начинается с этого сообщения: \"$firstMessage\". Ответь только названием без кавычек и лишних слов."
            
            val response = geminiApi.generateContent(
                apiKey = BuildConfig.GEMINI_API_KEY,
                request = GeminiRequest(
                    contents = listOf(
                        Content(role = "user", parts = listOf(Part(prompt)))
                    )
                )
            )
            
            val generatedTitle = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            if (!generatedTitle.isNullOrBlank()) {
                chatDao.getChatById(chatId)?.let { chat ->
                    chatDao.insertChat(
                        chat.copy(
                            title = generatedTitle.trim().removeSurrounding("\""),
                            lastUpdateTime = System.currentTimeMillis()
                        )
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ChatRepository", "Failed to generate title: ${e.message}")
            // Если ИИ не ответил, просто используем начало сообщения
            chatDao.getChatById(chatId)?.let { chat ->
                chatDao.insertChat(chat.copy(title = firstMessage.take(50), lastUpdateTime = System.currentTimeMillis()))
            }
        }
    }

    private fun showNotification(title: String, content: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, PulseAiService.CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher))
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
}
