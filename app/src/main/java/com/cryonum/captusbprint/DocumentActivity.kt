package com.cryonum.captusbprint

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.hardware.usb.UsbManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.content.FileProvider
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
import com.cryonum.captusbprint.document.FirstPdfPrintGate
import com.cryonum.captusbprint.document.PdfBatchGate
import com.cryonum.captusbprint.usb.UsbDeviceProfiles
import com.cryonum.captusbprint.document.DocumentInspector
import com.cryonum.captusbprint.document.DocumentOverview
import com.cryonum.captusbprint.document.DocumentSession
import com.cryonum.captusbprint.document.PageOrientationChoice
import com.cryonum.captusbprint.core.HiscoaNative
import com.cryonum.captusbprint.document.PreparedPdfCaptPageBuilder
import com.cryonum.captusbprint.document.OfflinePdfHiscoaPreparation
import com.cryonum.captusbprint.core.JobSpecification
import com.cryonum.captusbprint.core.Orientation
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Stage C local preview plus an explicitly confirmed ONE-sheet PDF hardware experiment. */
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
    private lateinit var pickerButton: Button
    private lateinit var printPdfButton: Button
    private lateinit var printPdfBatchButton: Button
    private lateinit var cancelPdfButton: Button
    private lateinit var sharePdfReportButton: Button
    private val printCancelled = AtomicBoolean(false)
    private var printInProgress = false
    private var previewReady = false
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
                "Файл не передаётся в сеть. Печать одной страницы подтверждена пользователем; экспериментальные 2–3 листа требуют отдельного подтверждения."
            textSize = 16f
        })
        pickerButton = Button(this).apply {
            text = "Выбрать PDF / JPEG / PNG"
            setOnClickListener { pickFile() }
        }
        layout.addView(pickerButton)
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
            text = "Параметры задания (проверка отдельно от печати)"
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
            text = "Проверить PDF/HISCOA и CAPT-кадры (без печати)"
            setOnClickListener { verifyPdfBands() }
        }
        planSection.addView(rasterButton)
        printPdfButton = Button(this).apply {
            text = "Эксперимент: напечатать выбранную PDF-страницу (один лист)"
            setOnClickListener { confirmFirstPdfPrint() }
        }
        planSection.addView(printPdfButton)
        printPdfBatchButton = Button(this).apply {
            text = "Эксперимент: 2–3 PDF-листа в ОДНОМ задании"
            setOnClickListener { confirmPdfBatchPrint() }
        }
        planSection.addView(printPdfBatchButton)
        cancelPdfButton = Button(this).apply {
            text = "Отменить попытку (исход может быть неизвестен)"
            visibility = View.GONE
            setOnClickListener {
                if (printInProgress) {
                    printCancelled.set(true)
                    planResult.text = "Отмена запрошена. Принятый принтером лист может выйти. Автоматического повтора нет."
                }
            }
        }
        planSection.addView(cancelPdfButton)
        layout.addView(planSection)
        sharePdfReportButton = Button(this).apply {
            text = "Поделиться последним JSON-отчётом PDF-печати"
            isEnabled = FirstPdfPrintAttempt.reportFile(this@DocumentActivity).isFile
            setOnClickListener { sharePdfReport() }
        }
        layout.addView(sharePdfReportButton)
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
        val last = FirstPdfPrintAttempt.reportFile(this)
        if (last.isFile) {
            val phase = runCatching { org.json.JSONObject(last.readText()).optString("phase") }.getOrDefault("")
            if (phase == "USB_ATTEMPT_STARTED" || phase == "OUTCOME_UNKNOWN") {
                message.text = "Последний PDF-сеанс не имеет подтверждённого результата. Проверь физический лист; JSON можно отправить вручную. Автоповтора нет."
            }
        }
    }

    private fun pickFile() {
        if (printInProgress) return
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
        if (printInProgress) return
        previewReady = false
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
                    previewReady = true
                    preview.showDocument()
                    preview.showBitmap(bitmap, 0, doc.pageCount)
                    pageRange.setText("")
                    copies.setText("1")
                    planResult.text = ""
                    planSection.visibility = View.VISIBLE
                    rasterButton.visibility = if (doc.format.label == "PDF") View.VISIBLE else View.GONE
                    printPdfButton.visibility = if (doc.format.label == "PDF") View.VISIBLE else View.GONE
                    printPdfBatchButton.visibility = if (doc.format.label == "PDF") View.VISIBLE else View.GONE
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
        if (printInProgress) return
        val opened = session ?: return
        if (index !in 0 until opened.overview.pageCount) return
        val request = generation
        val previewRequest = ++previewGeneration
        previewReady = false
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
                    previewReady = true
                    preview.showBitmap(bitmap, index, opened.overview.pageCount)
                }, {
                    preview.showError("Не удалось показать страницу ${index + 1}. Выберите другой документ.")
                })
            }
        }
    }

    private fun checkJobPlan() {
        if (printInProgress) return
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
        if (opened.overview.format.label != "PDF" || rasterInProgress || printInProgress) return
        if (selectedOrientation() != Orientation.PORTRAIT) {
            planResult.text = "Офлайн-растр пока доступен только для книжной A4. Альбомное преобразование в CAPT ещё в разработке."
            return
        }
        val index = displayedPage
        val rotation = selectedRotation()
        val request = generation
        rasterInProgress = true
        rasterButton.isEnabled = false
        planResult.text = "Подготавливаем PDF, HISCOA и CAPT-кадры локально. USB-команды не отправляются…"
        worker.execute {
            val result = runCatching {
                val prepared = PreparedPdfCaptPageBuilder.prepare(
                    produceBands = { accept ->
                        opened.forEachPortraitPdfBand(
                            index,
                            rotation,
                            cancelled = { request != generation },
                            consumer = accept
                        )
                    },
                    encode = { packed, lineBytes, lines, last ->
                        HiscoaNative.encodeBand(packed, lineBytes, lines, last)
                    },
                    cancelled = { request != generation }
                )
                // Stage B previously observed 65280 bytes as a safe chunk size.
                // This is a dry-run ONLY; a future real job must query fresh
                // GetPrinterInfo after obtaining exclusive USB ownership.
                var videoFrames = 0
                var framedBytes = 0L
                prepared.forEachVideoFrame(65_280, cancelled = { request != generation }) { _, frame ->
                    videoFrames++
                    framedBytes += frame.size
                }
                "PDF-страница ${index + 1}: подготовлена одна A4 CAPT-страница. " +
                    "HISCOA-полос: ${prepared.bandCount}; растр: ${OfflinePdfHiscoaPreparation.EXPECTED_RASTER_BYTES} байт; " +
                    "сжато: ${prepared.compressedBytes} байт; максимальная полоса: " +
                    "${prepared.maximumBandBytes} байт; модельных CAPT-кадров при " +
                    "ранее проверенном лимите 65280: $videoFrames, $framedBytes байт. " +
                    "Данные не сохранены. USB/CAPT-команды не выполнялись."
            }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                rasterInProgress = false
                rasterButton.isEnabled = true
                if (request == generation) {
                    planResult.text = result.getOrElse {
                        "Офлайн-подготовка PDF/HISCOA/CAPT не завершилась (${it.javaClass.simpleName}). Ничего не печаталось."
                    }
                }
            }
        }
    }

    private fun confirmFirstPdfPrint() {
        val opened = session ?: return
        if (rasterInProgress || printInProgress || !previewReady) {
            planResult.text = "Дождись завершения предпросмотра или текущей подготовки."
            return
        }
        val page = displayedPage
        val rotation = selectedRotation()
        fun selectedJob() = JobSpecification.create(opened.overview.pageCount,
            pageRange.text.toString(), copies.text.toString().trim().toInt(),
            selectedOrientation(), contentRotationDegrees = selectedRotation())
        val plan = runCatching { selectedJob() }.getOrElse {
            planResult.text = "Некорректный диапазон или число копий. Для эксперимента выбери только текущую страницу и одну копию."
            return
        }
        FirstPdfPrintGate.rejection(opened.overview.format, plan, page)?.let {
            planResult.text = it
            return
        }
        val usb = getSystemService(USB_SERVICE) as UsbManager
        val matches = usb.deviceList.values.filter {
            UsbDeviceProfiles.identify(it.vendorId, it.productId)?.modelName == "Canon LBP2900"
        }
        if (matches.size != 1) {
            planResult.text = if (matches.isEmpty()) "Canon LBP2900 не обнаружен. Проверь подключение на главном экране."
                else "Несколько подходящих USB-принтеров: однозначный выбор невозможен."
            return
        }
        val device = matches.single()
        if (!usb.hasPermission(device)) {
            planResult.text = "Разреши USB-доступ к Canon на главном экране приложения."
            return
        }
        val key = device.deviceName // Only for process-local lookup; never exported.
        val request = generation
        AlertDialog.Builder(this)
            .setTitle("Первый настоящий отпечаток PDF")
            .setMessage("Будут отправлены реальные команды принтеру. Только страница ${page + 1} из ${opened.overview.pageCount}, книжный A4, одна копия, поворот содержимого ${rotation}°.\n\nПоля около 11 мм пока не измерены. Если сеанс прервётся, результат может оказаться неизвестным; автоматического повтора не будет. Проверь наличие бумаги.")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Напечатать один лист") { _, _ ->
                val fresh = runCatching { selectedJob() }.getOrNull()
                if (request != generation || opened !== session || page != displayedPage ||
                    rotation != selectedRotation() || !previewReady || printInProgress ||
                    rasterInProgress || fresh == null ||
                    FirstPdfPrintGate.rejection(opened.overview.format, fresh, page) != null) {
                    planResult.text = "Документ или настройки изменились. Повтори проверку перед печатью."
                } else {
                    startFirstPdfPrint(opened, request, page, rotation, key)
                }
            }.show()
    }

    private fun startFirstPdfPrint(
        opened: DocumentSession, request: Int, page: Int, rotation: Int, key: String
    ) {
        printInProgress = true
        printCancelled.set(false)
        pickerButton.isEnabled = false
        rasterButton.isEnabled = false
        printPdfButton.isEnabled = false
        printPdfBatchButton.isEnabled = false
        sharePdfReportButton.isEnabled = false
        pageRange.isEnabled = false
        copies.isEnabled = false
        for (i in 0 until orientation.childCount) orientation.getChildAt(i).isEnabled = false
        rotateContent.isEnabled = false
        cancelPdfButton.visibility = View.VISIBLE
        planResult.text = "Подготовка одной PDF-страницы. USB пока не используется…"
        worker.execute {
            val summary = FirstPdfPrintAttempt.execute(applicationContext, opened, page,
                rotation, key, cancelled = { printCancelled.get() || request != generation }) { stage ->
                runOnUiThread {
                    if (!isFinishing && !isDestroyed && request == generation) planResult.text = stage
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                printInProgress = false
                pickerButton.isEnabled = true
                rasterButton.isEnabled = true
                printPdfButton.isEnabled = true
                printPdfBatchButton.isEnabled = true
                sharePdfReportButton.isEnabled = FirstPdfPrintAttempt.reportFile(this).isFile
                pageRange.isEnabled = true
                copies.isEnabled = true
                for (i in 0 until orientation.childCount) orientation.getChildAt(i).isEnabled = true
                rotateContent.isEnabled = true
                cancelPdfButton.visibility = View.GONE
                planResult.text = summary
            }
        }
    }

    /** Separate owner-confirmed pilot: 2..3 sheets, ONE CAPT ReserveUnit.
     * Does not silently convert a general 2000-page plan into a smaller job.
     */
    private fun confirmPdfBatchPrint() {
        val opened = session ?: return
        if (rasterInProgress || printInProgress || !previewReady) {
            planResult.text = "Дождись окончания предпросмотра или подготовки."
            return
        }
        val page = displayedPage
        val rotation = selectedRotation()
        fun selectedJob() = JobSpecification.create(opened.overview.pageCount,
            pageRange.text.toString(), copies.text.toString().trim().toInt(),
            selectedOrientation(), contentRotationDegrees = selectedRotation())
        val frozen = runCatching { selectedJob() }.getOrElse {
            planResult.text = "Проверь диапазон и число копий для многостраничного теста."
            return
        }
        PdfBatchGate.rejection(opened.overview.format, frozen, page)?.let {
            planResult.text = it
            return
        }
        val usb = getSystemService(USB_SERVICE) as UsbManager
        val matches = usb.deviceList.values.filter {
            UsbDeviceProfiles.identify(it.vendorId, it.productId)?.modelName == "Canon LBP2900"
        }
        if (matches.size != 1) {
            planResult.text = "Для теста нужен ровно один подключённый Canon LBP2900."
            return
        }
        val device = matches.single()
        if (!usb.hasPermission(device)) {
            planResult.text = "Сначала предоставь USB-разрешение на главном экране."
            return
        }
        val request = generation
        val key = device.deviceName
        val selectedNumbers = frozen.outputPageOrder.joinToString(", ") { (it + 1).toString() }
        AlertDialog.Builder(this)
            .setTitle("Эксперимент: одно многостраничное задание")
            .setMessage("Будут напечатаны ${frozen.outputPageOrder.size} листа в одном CAPT-задании. " +
                "Исходные страницы в порядке выдачи: $selectedNumbers. " +
                "Копий: ${frozen.copies}. A4 книжная, поворот содержимого $rotation°.\n\n" +
                "Это новый аппаратно НЕ проверенный режим. При остановке часть листов уже может выйти. " +
                "Зависание ReserveUnit 0x8C не устранено, автоматического повтора и сброса принтера нет. " +
                "Проверь бумагу и нажми кнопку только один раз.")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Напечатать ${frozen.outputPageOrder.size} листа") { _, _ ->
                val fresh = runCatching { selectedJob() }.getOrNull()
                if (request != generation || opened !== session || page != displayedPage ||
                    rotation != selectedRotation() || !previewReady || printInProgress ||
                    rasterInProgress || fresh != frozen ||
                    PdfBatchGate.rejection(opened.overview.format, frozen, page) != null) {
                    planResult.text = "Настройки или предпросмотр изменились. Повтори проверку."
                } else {
                    startPdfBatchPrint(opened, frozen, request, key)
                }
            }.show()
    }

    private fun startPdfBatchPrint(
        opened: DocumentSession, plan: JobSpecification, request: Int, key: String
    ) {
        printInProgress = true
        printCancelled.set(false)
        pickerButton.isEnabled = false
        rasterButton.isEnabled = false
        printPdfButton.isEnabled = false
        printPdfBatchButton.isEnabled = false
        sharePdfReportButton.isEnabled = false
        pageRange.isEnabled = false
        copies.isEnabled = false
        for (i in 0 until orientation.childCount) orientation.getChildAt(i).isEnabled = false
        rotateContent.isEnabled = false
        cancelPdfButton.visibility = View.VISIBLE
        planResult.text = "Подготовка ограниченного PDF-задания, пока без USB…"
        worker.execute {
            val summary = PdfBatchPrintAttempt.execute(applicationContext, opened, plan,
                key, cancelled = { printCancelled.get() || request != generation }) { progress ->
                runOnUiThread {
                    if (!isFinishing && !isDestroyed && request == generation) {
                        planResult.text = progress
                    }
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                printInProgress = false
                pickerButton.isEnabled = true
                rasterButton.isEnabled = true
                printPdfButton.isEnabled = true
                printPdfBatchButton.isEnabled = true
                sharePdfReportButton.isEnabled = FirstPdfPrintAttempt.reportFile(this).isFile
                pageRange.isEnabled = true
                copies.isEnabled = true
                for (i in 0 until orientation.childCount) orientation.getChildAt(i).isEnabled = true
                rotateContent.isEnabled = true
                cancelPdfButton.visibility = View.GONE
                planResult.text = summary
            }
        }
    }

    /** The report is never sent until the user explicitly invokes Sharesheet. */
    private fun sharePdfReport() {
        try {
            val stored = FirstPdfPrintAttempt.reportFile(this)
            if (!stored.isFile) return
            val directory = File(cacheDir, "reports").apply { mkdirs() }
            val copy = File.createTempFile("capt-pdf-print-", ".json", directory)
            copy.writeText(stored.readText(Charsets.UTF_8), Charsets.UTF_8)
            directory.listFiles()?.sortedByDescending { it.lastModified() }
                ?.drop(24)?.forEach { it.delete() }
            val uri = FileProvider.getUriForFile(this,
                "${BuildConfig.APPLICATION_ID}.reportprovider", copy)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(contentResolver, copy.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Поделиться JSON PDF-печати"))
        } catch (_: Exception) {
            message.text = "Не удалось подготовить PDF-отчёт для отправки."
        }
    }

    override fun onDestroy() {
        printCancelled.set(true)
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
