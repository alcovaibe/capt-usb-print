package com.cryonum.captusbprint

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.cryonum.captusbprint.document.DocumentInspector
import com.cryonum.captusbprint.document.DocumentOverview
import com.cryonum.captusbprint.document.DocumentSession
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
    private lateinit var planSection: LinearLayout
    private lateinit var pageRange: EditText
    private lateinit var copies: EditText
    private lateinit var orientation: RadioGroup
    private lateinit var planResult: TextView
    private lateinit var preview: DocumentPreviewPanel
    private var inspectedDocument: DocumentOverview? = null
    private var session: DocumentSession? = null
    private var displayedBitmap: Bitmap? = null
    private var displayedPage = 0
    private var previewGeneration = 0
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
            text = "PDF, PNG и JPEG: локальный постраничный предпросмотр и план задания. " +
                "Файл не передаётся в сеть. Предпросмотр не отправляет документ на принтер."
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
        preview = DocumentPreviewPanel(this).apply {
            onPrevious = { requestPreview(displayedPage - 1) }
            onNext = { requestPreview(displayedPage + 1) }
        }
        layout.addView(preview, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = 20
        })
        planSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        planSection.addView(TextView(this).apply {
            text = "Параметры будущего задания · проверка без печати"
            textSize = 18f
        })
        planSection.addView(TextView(this).apply {
            text = "Страницы (например: 1–3, 5, 8). Пустое поле — все страницы."
        })
        pageRange = EditText(this).apply {
            hint = "Все страницы"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        planSection.addView(pageRange)
        planSection.addView(TextView(this).apply { text = "Количество копий (1–20)" })
        copies = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setText("1")
        }
        planSection.addView(copies)
        planSection.addView(TextView(this).apply { text = "Ориентация A4" })
        orientation = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            addView(RadioButton(this@DocumentActivity).apply {
                id = View.generateViewId()
                text = "Книжная"
                isChecked = true
                tag = Orientation.PORTRAIT
            })
            addView(RadioButton(this@DocumentActivity).apply {
                id = View.generateViewId()
                text = "Альбомная"
                tag = Orientation.LANDSCAPE
            })
        }
        planSection.addView(orientation)
        planSection.addView(Button(this).apply {
            text = "Проверить план задания (без печати)"
            setOnClickListener { checkJobPlan() }
        })
        planResult = TextView(this).apply {
            textSize = 16f
            setTextIsSelectable(true)
        }
        planSection.addView(planResult)
        layout.addView(planSection)
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
        inspectedDocument = null
        planSection.visibility = View.GONE
        message.text = "Проверка выбранного документа… На принтер ничего не отправляется."
        worker.execute {
            val outcome = runCatching { DocumentInspector.inspect(applicationContext, uri) }
            runOnUiThread {
                if (isFinishing || isDestroyed || request != generation) return@runOnUiThread
                message.text = outcome.fold({ doc ->
                    inspectedDocument = doc
                    pageRange.setText("")
                    copies.setText("1")
                    (orientation.getChildAt(0) as RadioButton).isChecked = true
                    planResult.text = ""
                    planSection.visibility = View.VISIBLE
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

    private fun checkJobPlan() {
        val doc = inspectedDocument ?: run {
            planResult.text = "Сначала выбери и проверь документ."
            return
        }
        val count = copies.text.toString().trim().toIntOrNull()
        if (count == null) {
            planResult.text = "Укажи целое количество копий от 1 до 20."
            return
        }
        val selectedOrientation = if ((orientation.getChildAt(1) as RadioButton).isChecked) {
            Orientation.LANDSCAPE
        } else {
            Orientation.PORTRAIT
        }
        val plan = runCatching {
            JobSpecification.create(
                sourcePageCount = doc.pageCount,
                pages = pageRange.text.toString(),
                copies = count,
                orientation = selectedOrientation
            )
        }
        planResult.text = plan.fold({ job ->
            val order = job.outputPageOrder
            val preview = order.take(16).joinToString(", ") { (it + 1).toString() } +
                if (order.size > 16) ", …" else ""
            "План корректен. Формат: ${doc.format.label}; A4; " +
                "ориентация: ${if (job.orientation == Orientation.PORTRAIT) "книжная" else "альбомная"}.\n" +
                "Исходных страниц выбрано: ${job.pageIndices.size}; копий: ${job.copies}; " +
                "листов в задании: ${order.size}.\n" +
                "Порядок (номера страниц исходного документа): $preview.\n" +
                "Только проверка параметров: файл не сохранён для очереди; " +
                "USB/CAPT-команды не выполнялись."
        }, {
            "Некорректные параметры: проверь диапазоны, повторы, порядок страниц и число копий. " +
                "Ограничение — до 2000 итоговых листов. Ничего не напечатано."
        })
    }

    override fun onDestroy() {
        generation++
        worker.shutdownNow()
        super.onDestroy()
    }

    companion object { private const val REQUEST_DOCUMENT = 101 }
}
