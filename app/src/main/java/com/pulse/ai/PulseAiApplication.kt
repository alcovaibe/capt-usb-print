package com.pulse.ai

import android.app.Application
import androidx.room.Room
import com.pulse.ai.data.ChatRepository
import com.pulse.ai.data.local.ChatDatabase
import com.pulse.ai.data.remote.GeminiApi
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class PulseAiApplication : Application() {
    
    val database by lazy {
        Room.databaseBuilder(
            this,
            ChatDatabase::class.java,
            "pulse_ai_db"
        ).fallbackToDestructiveMigration().build()
    }

    val api by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl("https://generativelanguage.googleapis.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .client(client)
            .build()

        retrofit.create(GeminiApi::class.java)
    }

    val repository by lazy {
        ChatRepository(this, database.chatDao(), api)
    }

    override fun onCreate() {
        super.onCreate()
    }
}
