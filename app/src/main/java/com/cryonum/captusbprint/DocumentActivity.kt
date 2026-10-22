package com.cryonum.captusbprint

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.cryonum.captusbprint.document.DocumentInspector
import com.cryonum.captusbprint.document.DocumentOverview
import com.cryonum.captusbprint.core.JobSpecification
import com.cryonum.captusbprint.core.Orientation
import java.util.concurrent.Executors

/**
 * Stage C entry point: local intake and metadata only. Nothing here sends
 * document contents or USB/CAPT commands, or uploads content to any service.
 */
class DocumentActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var message: TextView
    private lateinit var rangeInput: EditText
    private lateinit var copiesInput: EditText
    private lateinit var orientationInput: Spinner
    private lateinit var planButton: Button
    private lateinit var planResult: TextView
    private var overview: DocumentOverview? = null
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
                "Можно посмотреть размеры страниц и сформировать план копий. " +
                "Предпросмотр и отправка документа принтеру ещё не включены."
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
        layout.addView(TextView(this).apply {
            text = "План печати (пока без передачи на принтер)"
            textSize = 19f
        })
        rangeInput = EditText(this).apply {
            hint = "Страницы: 1-3, 5, 8 (пусто — все)"
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
        }
        layout.addView(rangeInput)
        copiesInput = EditText(this).apply {
            hint = "Количество копий (1–20)"
            setText("1")
            inputType = InputType.TYPE_CLASS_NUMBER
            isSingleLine = true
        }
        layout.addView(copiesInput)
        orientationInput = Spinner(this).apply {
            adapter = ArrayAdapter(this@DocumentActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Книжная", "Альбомная"))
        }
        layout.addView(orientationInput)
        planButton = Button(this).apply {
            text = "Сформировать план (без печати)"
            isEnabled = false
            setOnClickListener { createOfflinePlan() }
        }
        layout.addView(planButton)
        planResult = TextView(this).apply {
            text = "План доступен после проверки документа."
            textSize = 15f
            setTextIsSelectable(true)
        }
        layout.addView(planResult)
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
        overview = null
        planButton.isEnabled = false
        planResult.text = "План доступен после успешной проверки документа."
        message.text = "Проверка выбранного документа… На принтер ничего не отправляется."
        worker.execute {
            val outcome = runCatching { DocumentInspector.inspect(applicationContext, uri) }
            runOnUiThread {
                if (isFinishing || isDestroyed || request != generation) return@runOnUiThread
                message.text = outcome.fold({ doc ->
                    overview = doc
                    planButton.isEnabled = true
                    val rotation = doc.rotationDegrees?.let { "; EXIF: ${it}°" } ?: ""
                    val pageDetails = if (doc.format.label == "PDF") {
                        val displayed = doc.pageSizes.take(20).mapIndexed { index, size ->
                            "${index + 1}: ${size.width} × ${size.height} pt"
                        }.joinToString("\n")
                        "\nРазмер каждой страницы (первые 20):\n" + displayed +
                            if (doc.pageCount > 20) "\n…ещё ${doc.pageCount - 20} страниц" else ""
                    } else ""
                    "${doc.format.label}: ${doc.pageCount} стр.; первая страница " +
                        "${doc.width} × ${doc.height} ${doc.dimensionsUnit}${rotation}; " +
                        "${doc.copiedBytes} байт." + pageDetails + "\n" +
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

    private fun createOfflinePlan() {
        val doc = overview ?: return
        val copies = copiesInput.text.toString().toIntOrNull() ?: 0
        val orientation = if (orientationInput.selectedItemPosition == 1)
            Orientation.LANDSCAPE else Orientation.PORTRAIT
        val planned = runCatching {
            JobSpecification.create(doc.pageCount, rangeInput.text.toString(), copies,
                orientation)
        }
        planResult.text = planned.fold({ job ->
            val order = job.outputPageOrder
            val sample = order.take(24).joinToString(", ") { (it + 1).toString() }
            "Только план, без печати. A4, " +
                (if (job.orientation == Orientation.PORTRAIT) "книжная" else "альбомная") +
                " ориентация; выбранных страниц: ${job.pageIndices.size}; " +
                "копий: ${job.copies}; листов в плане: ${order.size}.\n" +
                "Порядок страниц (первые 24): ${sample}" +
                (if (order.size > 24) ", …" else "") +
                "\nНа принтер ничего не отправлено."
        }, { error ->
            "Некорректный диапазон или количество копий: " +
                (error.message ?: "проверь параметры") + ". Печать не выполнялась."
        })
    }

    override fun onDestroy() {
        generation++
        worker.shutdownNow()
        super.onDestroy()
    }

    companion object { private const val REQUEST_DOCUMENT = 101 }
}
