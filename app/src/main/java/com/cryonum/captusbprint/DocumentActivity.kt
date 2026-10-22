package com.cryonum.captusbprint

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import com.cryonum.captusbprint.document.PageOrientationChoice
import com.cryonum.captusbprint.core.CaptTestPage
import com.cryonum.captusbprint.core.JobSpecification
import com.cryonum.captusbprint.core.Orientation
import java.util.concurrent.Executors

/**
 * Stage C offline local preview, job planning and optional PDF band verification.
 * None of these document actions sends USB/CAPT commands or uploads content.
 */
class DocumentActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var message: TextView
    private lateinit var planSection: LinearLayout
    private lateinit var pageRange: EditText
    private lateinit var copies: EditText
    private lateinit var orientation: RadioGroup
    private lateinit var planResult: TextView
    private lateinit var rotateContent: android.widget.CheckBox
    private lateinit var rasterButton: Button
    private var rasterInProgress = false
    private lateinit var preview: DocumentPreviewPanel
    private var inspectedDocument: DocumentOverview? = null
    private var session: DocumentSession? = null
    private var displayedBitmap: Bitmap? = null
    private var displayedPage = 0
    private var previewGeneration = 0
    @Volatile private var generation = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(20))
        }
        layout.addView(TextView(this).apply {
            text = "Этап C · Локальные документы"
            textSize = 22f
        })
        layout.addView(TextView(this).apply {
            text = "PDF, PNG и JPEG: локальный предпросмотр размещения на A4 и план задания. " +
                "Файл не передаётся в сеть. Печать документа пока не включена."
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
        planSection.addView(TextView(this).apply {
            text = "Ориентация A4 (авто — по первой странице)"
        })
        orientation = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            addView(RadioButton(this@DocumentActivity).apply {
                id = View.generateViewId()
                text = "Авто (по первой странице)"
                isChecked = true
                tag = PageOrientationChoice.AUTO
            })
            addView(RadioButton(this@DocumentActivity).apply {
                id = View.generateViewId()
                text = "Книжная"
                tag = PageOrientationChoice.PORTRAIT
            })
            addView(RadioButton(this@DocumentActivity).apply {
                id = View.generateViewId()
                text = "Альбомная"
                tag = PageOrientationChoice.LANDSCAPE
            })
        }
        orientation.setOnCheckedChangeListener { _, _ ->
            // Orientation changes re-render the same page on the logical A4 sheet.
            if (session != null) requestPreview(displayedPage)
        }
        planSection.addView(orientation)
        rotateContent = android.widget.CheckBox(this).apply {
            text = "Повернуть содержимое на 90° по часовой стрелке"
            setOnCheckedChangeListener { _, _ ->
                if (session != null) requestPreview(displayedPage)
            }
        }
        planSection.addView(rotateContent)
        planSection.addView(TextView(this).apply {
            text = "Книжный документ на альбомном листе без поворота будет меньше — это режим «Вписать»."
            textSize = 13f
        })
        planSection.addView(Button(this).apply {
            text = "Проверить план задания (без печати)"
            setOnClickListener { checkJobPlan() }
        })
        planResult = TextView(this).apply {
            textSize = 16f
            setTextIsSelectable(true)
        }
        planSection.addView(planResult)
        rasterButton = Button(this).apply {
            text = "Проверить PDF-растр текущей страницы (без печати)"
            setOnClickListener { verifyPdfBands() }
        }
        planSection.addView(rasterButton)
        layout.addView(planSection)
        val scrolling = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = true
            addView(layout)
        }
        // Apply insets to the scrolling viewport, NOT the scrolling content:
        // otherwise the text can scroll underneath Android 16 status/nav bars.
        val spacing = dp(8)
        ViewCompat.setOnApplyWindowInsetsListener(scrolling) { view, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            view.setPadding(spacing + safe.left, safe.top,
                spacing + safe.right, safe.bottom)
            insets
        }
        setContentView(scrolling)
        ViewCompat.requestApplyInsets(scrolling)
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
        ++previewGeneration
        val previous = session
        session = null
        inspectedDocument = null
        (orientation.getChildAt(0) as RadioButton).isChecked = true
        rotateContent.isChecked = false
        displayedBitmap?.let {
            preview.clearImage()
            it.recycle()
            displayedBitmap = null
        }
        preview.visibility = View.GONE
        planSection.visibility = View.GONE
        message.text = "Проверка выбранного документа и подготовка первой страницы…"
        worker.execute {
            // The worker serializes all renders; never delete a source mid-render.
            previous?.close()
            val outcome = runCatching {
                val opened = DocumentInspector.openSession(applicationContext, uri)
                try {
                    opened to opened.renderPreview(0, 1000, 1450,
                        PageOrientationChoice.AUTO.resolve(opened.overview.width,
                            opened.overview.height))
                } catch (error: Throwable) {
                    opened.close()
                    throw error
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed || request != generation) {
                    outcome.getOrNull()?.let { (opened, bitmap) ->
                        bitmap.recycle()
                        opened.close()
                    }
                    return@runOnUiThread
                }
                message.text = outcome.fold({ (opened, bitmap) ->
                    session = opened
                    val doc = opened.overview
                    inspectedDocument = doc
                    displayedBitmap = bitmap
                    displayedPage = 0
                    preview.showDocument()
                    preview.showBitmap(bitmap, 0, doc.pageCount)
                    pageRange.setText("")
                    copies.setText("1")
                    planResult.text = ""
                    planSection.visibility = View.VISIBLE
                    rasterButton.visibility = if (doc.format.label == "PDF") View.VISIBLE else View.GONE
                    val rotation = doc.rotationDegrees?.let { "; EXIF: ${it}°" } ?: ""
                    "${doc.format.label}: ${doc.pageCount} стр.; " +
                        "${doc.width} × ${doc.height} ${doc.dimensionsUnit}${rotation}; " +
                        "${doc.copiedBytes} байт.\n" +
                        "Предпросмотр A4 (вписать; программный отступ около 11 мм) готов. " +
                        "Фактическая геометрия печати ещё не проверена; USB-команды не выполнялись."
                }, { error ->
                    preview.visibility = View.GONE
                    when (error) {
                        is SecurityException -> "Нет доступа к документу или PDF защищён паролем."
                        is IllegalArgumentException -> "Файл не поддерживается, повреждён или превышает безопасные пределы."
                        else -> "Не удалось открыть предпросмотр. Проверь доступность файла и свободное место."
                    } + "\nПечатные команды не отправлялись."
                })
            }
        }
    }

    private fun requestPreview(index: Int) {
        val opened = session ?: return
        if (index !in 0 until opened.overview.pageCount) return
        val request = generation
        val previewRequest = ++previewGeneration
        val requestedOrientation = selectedOrientation()
        val rotation = selectedRotation()
        preview.setLoading()
        worker.execute {
            if (request != generation) return@execute
            val outcome = runCatching { opened.renderPreview(index, 1000, 1450, requestedOrientation, rotation) }
            runOnUiThread {
                if (isFinishing || isDestroyed || request != generation ||
                    previewRequest != previewGeneration) {
                    outcome.getOrNull()?.recycle()
                    return@runOnUiThread
                }
                outcome.fold({ bitmap ->
                    preview.clearImage()
                    displayedBitmap?.recycle()
                    displayedBitmap = bitmap
                    displayedPage = index
                    preview.showBitmap(bitmap, index, opened.overview.pageCount)
                }, {
                    preview.showError("Не удалось показать страницу ${index + 1}. Выберите другой документ.")
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
        val chosenOrientation = selectedOrientation()
        val plan = runCatching {
            JobSpecification.create(
                sourcePageCount = doc.pageCount,
                pages = pageRange.text.toString(),
                copies = count,
                orientation = chosenOrientation,
                contentRotationDegrees = selectedRotation()
            )
        }
        planResult.text = plan.fold({ job ->
            val order = job.outputPageOrder
            val preview = order.take(16).joinToString(", ") { (it + 1).toString() } +
                if (order.size > 16) ", …" else ""
            "План корректен. Формат: ${doc.format.label}; A4; " +
                "ориентация: ${if (job.orientation == Orientation.PORTRAIT) "книжная" else "альбомная"}.\n" +
                "Поворот содержимого: ${job.contentRotationDegrees}°. " +
                "Автоориентация определяется по первой странице.\n" +
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

    private fun selectedOrientation(): Orientation {
        val first = inspectedDocument ?: return Orientation.PORTRAIT
        val index = orientation.checkedRadioButtonId
        val choice = (findViewById<RadioButton>(index)?.tag as? PageOrientationChoice)
            ?: PageOrientationChoice.AUTO
        return choice.resolve(first.width, first.height)
    }

    private fun selectedRotation(): Int = if (rotateContent.isChecked) 90 else 0

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    /** Only offline band preparation. This does NOT reserve/claim the USB device. */
    private fun verifyPdfBands() {
        val opened = session ?: return
        if (opened.overview.format.label != "PDF" || rasterInProgress) return
        if (selectedOrientation() != Orientation.PORTRAIT) {
            planResult.text = "Офлайн-растр пока доступен только для книжной A4. Альбомное преобразование в CAPT ещё в разработке."
            return
        }
        val index = displayedPage
        val rotation = selectedRotation()
        val request = generation
        rasterInProgress = true
        rasterButton.isEnabled = false
        planResult.text = "Готовим PDF последовательно полосами по 64 строки. USB-команды не отправляются…"
        worker.execute {
            val result = runCatching {
                var count = 0
                var totalBytes = 0L
                var expectedY = 0
                opened.forEachPortraitPdfBand(index, rotation,
                    cancelled = { request != generation }
                ) { start, packed ->
                    check(start == expectedY) { "Non-sequential band" }
                    val lines = minOf(CaptTestPage.BAND_LINES, CaptTestPage.HEIGHT - start)
                    check(packed.size == CaptTestPage.LINE_BYTES * lines)
                    expectedY += lines
                    count++
                    totalBytes += packed.size
                }
                check(expectedY == CaptTestPage.HEIGHT)
                "PDF-страница ${index + 1}: подготовлено полос: $count; " +
                    "монохромных данных: $totalBytes байт. Только RAM; USB/CAPT не использовались."
            }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                rasterInProgress = false
                rasterButton.isEnabled = true
                if (request == generation) {
                    planResult.text = result.getOrElse {
                        "Офлайн-подготовка PDF не завершилась (${it.javaClass.simpleName}). Ничего не печаталось."
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        generation++
        ++previewGeneration
        preview.clearImage()
        displayedBitmap?.recycle()
        displayedBitmap = null
        val owned = session
        session = null
        // Enqueue deletion after all active preview jobs have finished.
        worker.execute { owned?.close() }
        worker.shutdown()
        super.onDestroy()
    }

    companion object { private const val REQUEST_DOCUMENT = 101 }
}
