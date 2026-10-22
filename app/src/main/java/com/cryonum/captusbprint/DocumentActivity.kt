package com.cryonum.captusbprint

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.cryonum.captusbprint.document.DocumentInspector
import java.util.concurrent.Executors

/**
 * Stage C entry point: local intake and metadata only. Nothing here sends
 * document contents or USB/CAPT commands, or uploads content to any service.
 */
class DocumentActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var message: TextView
    private var generation = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 40, 24, 24)
        }
        layout.addView(TextView(this).apply {
            text = "Этап C · Локальные документы"
            textSize = 22f
        })
        layout.addView(TextView(this).apply {
            text = "Проверка PDF, JPEG и PNG без печати. Файл не передаётся в сеть. " +
                "Предпросмотр и отправка документа принтеру появятся на следующем шаге."
            textSize = 16f
        })
        layout.addView(Button(this).apply {
            text = "Выбрать PDF / JPEG / PNG"
            setOnClickListener { pickFile() }
        })
        message = TextView(this).apply {
            text = "Выбери локальный документ для проверки."
            textSize = 16f
            setPadding(0, 16, 0, 16)
            setTextIsSelectable(true)
        }
        layout.addView(message)
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(layout)
            if (Build.VERSION.SDK_INT >= 30) {
                setOnApplyWindowInsetsListener { _, insets ->
                    val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or
                        android.view.WindowInsets.Type.displayCutout())
                    layout.setPadding(24 + bars.left, 24 + bars.top,
                        24 + bars.right, 24 + bars.bottom)
                    insets
                }
                requestApplyInsets()
            }
        })
        if (intent?.action == Intent.ACTION_SEND) {
            val shared = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            } ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            if (shared != null) inspect(shared)
            else message.text = "В переданном сообщении нет доступного файла."
        }
    }

    private fun pickFile() {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES,
                arrayOf("application/pdf", "image/jpeg", "image/png"))
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(picker, REQUEST_DOCUMENT)
        } catch (_: ActivityNotFoundException) {
            message.text = "Системный выбор файлов недоступен."
        }
    }

    @Deprecated("Activity result callback for framework ACTION_OPEN_DOCUMENT")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_DOCUMENT && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri != null) inspect(uri)
            else message.text = "Файловый провайдер не вернул документ."
        }
    }

    private fun inspect(uri: Uri) {
        val request = ++generation
        message.text = "Проверка выбранного документа… На принтер ничего не отправляется."
        worker.execute {
            val outcome = runCatching { DocumentInspector.inspect(applicationContext, uri) }
            runOnUiThread {
                if (isFinishing || isDestroyed || request != generation) return@runOnUiThread
                message.text = outcome.fold({ doc ->
                    val rotation = doc.rotationDegrees?.let { "; EXIF: ${it}°" } ?: ""
                    "${doc.format.label}: ${doc.pageCount} стр.; " +
                        "${doc.width} × ${doc.height} ${doc.dimensionsUnit}${rotation}; " +
                        "${doc.copiedBytes} байт.\n" +
                        "Проверка успешна. Печать и загрузка в сеть не выполнялись."
                }, { error ->
                    when (error) {
                        is SecurityException -> "Нет доступа к документу или PDF защищён паролем."
                        is IllegalArgumentException -> "Файл не поддерживается, повреждён или превышает безопасные пределы проверки."
                        else -> "Не удалось прочитать документ. Проверь доступность файла и свободное место."
                    } + "\nПечатные команды не отправлялись."
                })
            }
        }
    }

    override fun onDestroy() {
        generation++
        worker.shutdownNow()
        super.onDestroy()
    }

    companion object { private const val REQUEST_DOCUMENT = 101 }
}
