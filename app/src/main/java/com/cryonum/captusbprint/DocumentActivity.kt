package com.cryonum.captusbprint

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.cryonum.captusbprint.core.JobSpecification
import com.cryonum.captusbprint.core.Orientation
import com.cryonum.captusbprint.document.*
import com.cryonum.captusbprint.office.*
import java.util.UUID
import com.cryonum.captusbprint.queue.PrintQueueActivity
import com.cryonum.captusbprint.queue.PrintQueueRuntime
import com.cryonum.captusbprint.queue.PrintQueueService
import com.cryonum.captusbprint.usb.UsbDeviceProfiles
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** Normal documents use the foreground queue; the explicit one-sheet PDF pilot is cancellable. */
class DocumentActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var message: TextView
    private lateinit var status: TextView
    private lateinit var pageRange: EditText
    private lateinit var copies: EditText
    private lateinit var orientation: RadioGroup
    private lateinit var rotation: CheckBox
    private lateinit var preview: DocumentPreviewPanel
    private lateinit var print: Button
    private lateinit var pick: Button
    private lateinit var cancelImport: Button
    private lateinit var printActions: LinearLayout
    private lateinit var testPrint: Button
    private lateinit var cancelTestPrint: Button
    private val testCancelled = AtomicBoolean(false)
    private var testRunning = false
    private var session: DocumentSession? = null
    private var bitmap: Bitmap? = null
    private var sourceUri: Uri? = null
    private var displayedPage = 0
    @Volatile private var generation = 0
    @Volatile private var previewGeneration = 0
    private var previewReady = false
    private var copying = false
    private var importing = false
    private var visible = false
    private var restoring: Bundle? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoring = savedInstanceState
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,24,24,24) }
        fun label(res: Int) { layout.addView(TextView(this).apply { setText(res); textSize = 16f }) }
        label(R.string.document_title)
        pick = Button(this).apply {
            setText(R.string.document_pick)
            setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE)
                    putExtra(Intent.EXTRA_MIME_TYPES, DocumentIntake.MIME_TYPES)
                }
                runCatching { @Suppress("DEPRECATION") startActivityForResult(intent, 101) }
                    .onFailure { message.setText(R.string.document_open_failed) }
            }
        }
        layout.addView(pick)
        cancelImport = Button(this).apply {
            setText(R.string.document_cancel_import)
            visibility = View.GONE
            setOnClickListener {
                ++generation; ++previewGeneration
                importing = false; sourceUri = null; restoring = null
                preview.showError(getString(R.string.document_import_cancelled))
                message.setText(R.string.document_import_cancelled)
                updatePrintActions()
            }
        }
        layout.addView(cancelImport)
        layout.addView(Button(this).apply {
            setText(R.string.queue_title)
            setOnClickListener { startActivity(Intent(this@DocumentActivity, PrintQueueActivity::class.java)) }
        })
        message = TextView(this).apply { setText(R.string.document_pick_hint); setTextIsSelectable(true) }
        layout.addView(message)
        preview = DocumentPreviewPanel(this).apply {
            onPrevious = { requestPreview(displayedPage - 1) }
            onNext = { requestPreview(displayedPage + 1) }
        }
        layout.addView(preview)
        label(R.string.document_pages)
        pageRange = EditText(this).apply { setSingleLine(true); hint = getString(R.string.document_all_pages) }
        layout.addView(pageRange)
        label(R.string.document_copies)
        copies = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER; setSingleLine(true)
            setText(getPreferences(MODE_PRIVATE).getInt("copies", 1).coerceIn(1,20).toString())
        }
        layout.addView(copies)
        label(R.string.document_orientation)
        orientation = RadioGroup(this).apply {
            listOf(R.string.document_auto, R.string.document_portrait, R.string.document_landscape).forEachIndexed { i, textId ->
                addView(RadioButton(this@DocumentActivity).apply {
                    id = View.generateViewId(); setText(textId); tag = i; isChecked = i == 0
                })
            }
        }
        layout.addView(orientation)
        rotation = CheckBox(this).apply { setText(R.string.document_rotate) }
        layout.addView(rotation)
        orientation.setOnCheckedChangeListener { _, _ -> requestPreview(displayedPage) }
        rotation.setOnCheckedChangeListener { _, _ -> requestPreview(displayedPage) }
        status = TextView(this).apply { setTextIsSelectable(true) }
        layout.addView(status)
        print = Button(this).apply { setText(R.string.document_print); setOnClickListener { confirmPrint() } }
        testPrint = Button(this).apply {
            setText(R.string.document_test_print)
            setOnClickListener { confirmTestPrint() }
        }
        cancelTestPrint = Button(this).apply {
            setText(R.string.cancel_test_page)
            setOnClickListener {
                testCancelled.set(true)
                status.setText(R.string.test_page_cancel_requested)
            }
        }
        layout.addView(Button(this).apply {
            setText(R.string.document_report)
            setOnClickListener { sharePdfReport() }
        })
        val scroll = ScrollView(this).apply { addView(layout) }
        printActions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 0, 24, 16)
            addView(print); addView(testPrint); addView(cancelTestPrint)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(printActions)
        }
        updatePrintActions()
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom); insets
        }
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
        val restored = savedInstanceState?.getString("uri")?.let { Uri.parse(it) }
        val shared = if (intent?.action == Intent.ACTION_SEND) {
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else { @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) }
        } else null
        (restored ?: shared ?: intent?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri)?.let { inspect(it) }
    }
    override fun onStart() { super.onStart(); visible = true }
    override fun onStop() {
        visible = false
        if (testRunning) testCancelled.set(true)
        super.onStop()
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 101 && resultCode == RESULT_OK) data?.data?.let {
            restoring = null; inspect(it)
        }
    }
    private fun inspect(uri: Uri) {
        if (copying) return
        sourceUri = uri
        val request = ++generation
        ++previewGeneration
        previewReady = false
        val old = session; session = null
        importing = true
        updatePrintActions()
        preview.clearImage(); bitmap?.recycle(); bitmap = null
        preview.setLoading(); message.setText(R.string.document_loading)
        worker.execute {
            old?.close()
            var officeSource: File? = null
            var officeKind: OfficeKind? = null
            val result = runCatching {
                OfficeWorkerClient.importSource(applicationContext, uri,
                    cancelled = { request != generation }, allowStandard = true).use { imported ->
                    val kind = imported.importedKind
                    if (kind != null) {
                        val directory = File(noBackupFilesDir, "office-ui/intake-${UUID.randomUUID()}")
                        officeSource = directory
                        check(directory.mkdirs())
                        java.nio.file.Files.move(imported.file.toPath(), File(directory, "source.bin").toPath())
                        officeKind = kind
                        null
                    } else {
                        val directory = File(cacheDir, "stage-c-inspection").apply { check(isDirectory || mkdirs()) }
                        val target = File(directory, "${UUID.randomUUID()}.bin")
                        java.nio.file.Files.move(imported.file.toPath(), target.toPath())
                        DocumentInspector.openCopiedSession(applicationContext, target) { request != generation }
                    }
                }
            }
            if (result.isFailure) {
                officeSource?.let { OfficePrivateFiles.deleteTree(it) }; officeSource = null
            }
            runOnUiThread {
                if (isDestroyed || isFinishing || request != generation) {
                    result.getOrNull()?.close()
                    officeSource?.let { directory -> Thread { OfficePrivateFiles.deleteTree(directory) }.start() }
                    return@runOnUiThread
                }
                importing = false
                updatePrintActions()
                result.fold({ source ->
                    if (source == null) {
                        val directory = requireNotNull(officeSource)
                        sourceUri = null; restoring = null
                        runCatching {
                            startActivity(Intent(this, OfficeDocumentActivity::class.java)
                                .putExtra("office-source", directory.name.removePrefix("intake-"))
                                .putExtra("office-kind", requireNotNull(officeKind).name))
                        }.onFailure {
                            worker.execute { OfficePrivateFiles.deleteTree(directory) }
                            message.setText(R.string.document_open_failed)
                        }
                        return@fold
                    }
                    runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    session = source
                    val meta = source.overview
                    message.text = getString(R.string.document_meta, meta.format.label,
                        meta.pageCount, meta.width, meta.height)
                    val saved = restoring; restoring = null
                    pageRange.setText(saved?.getString("pages") ?: "")
                    copies.setText(saved?.getString("copies") ?: getPreferences(MODE_PRIVATE)
                        .getInt("copies", 1).coerceIn(1,20).toString())
                    val choice = (saved?.getInt("orientation") ?: getPreferences(MODE_PRIVATE).getInt("orientation", 0)).coerceIn(0,2)
                    (orientation.getChildAt(choice) as RadioButton).isChecked = true
                    rotation.isChecked = saved?.getBoolean("rotation") ?: getPreferences(MODE_PRIVATE).getBoolean("rotation", false)
                    requestPreview((saved?.getInt("page") ?: 0).coerceIn(0, meta.pageCount-1))
                }, {
                    sourceUri = null
                    val text = getString(importFailure(it))
                    message.text = text
                    preview.showError(text)
                })
            }
        }
    }
    private fun selectedOrientation(): Orientation {
        val source = session ?: return Orientation.PORTRAIT
        return when (findViewById<RadioButton>(orientation.checkedRadioButtonId)?.tag as? Int ?: 0) {
            1 -> Orientation.PORTRAIT; 2 -> Orientation.LANDSCAPE
            else -> PageOrientationChoice.AUTO.resolve(source.overview.width, source.overview.height)
        }
    }
    private fun requestPreview(page: Int) {
        val source = session ?: return
        if (copying || page !in 0 until source.overview.pageCount) return
        val request = generation
        val render = ++previewGeneration
        val orientation = selectedOrientation()
        val degrees = if (rotation.isChecked) 90 else 0
        previewReady = false; preview.setLoading()
        updatePrintActions()
        worker.execute {
            val result = runCatching { source.renderPrintPreview(page, orientation, degrees) {
                request != generation || render != previewGeneration } }
            runOnUiThread {
                if (isDestroyed || isFinishing || request != generation || render != previewGeneration) {
                    result.getOrNull()?.recycle(); return@runOnUiThread
                }
                result.fold({ next ->
                    preview.clearImage(); bitmap?.recycle(); bitmap = next
                    displayedPage = page; previewReady = true
                    preview.showDocument(); preview.showBitmap(next, page, source.overview.pageCount)
                    status.setText(R.string.document_preview_ready)
                }, { preview.showError(getString(R.string.document_open_failed)) })
                updatePrintActions()
            }
        }
    }
    private fun plan(source: DocumentSession) = JobSpecification.create(source.overview.pageCount,
        pageRange.text.toString(), copies.text.toString().trim().toInt(), selectedOrientation(),
        contentRotationDegrees = if (rotation.isChecked) 90 else 0)

    private fun updatePrintActions() {
        cancelImport.visibility = if (importing) View.VISIBLE else View.GONE
        printActions.visibility = if (session != null) View.VISIBLE else View.GONE
        print.isEnabled = previewReady && !copying
        testPrint.visibility = if (session?.overview?.format == DocumentFormat.PDF) View.VISIBLE else View.GONE
        testPrint.isEnabled = previewReady && !copying
        cancelTestPrint.visibility = if (testRunning) View.VISIBLE else View.GONE
    }

    /** Fixed localized messages only; never display provider exception text or a source URI. */
    private fun importFailure(error: Throwable): Int = when (error) {
        is SecurityException -> R.string.document_access_failed
        is CancellationException -> R.string.document_import_cancelled
        is OfficeConversionFailure -> OfficePreviewMessages.resource(error.reason)
        is PrivateDocumentImport.Failure -> when (error.reason) {
            PrivateDocumentImport.Reason.TOO_LARGE -> R.string.document_import_too_large
            PrivateDocumentImport.Reason.NO_SPACE -> R.string.document_import_no_space
            PrivateDocumentImport.Reason.EMPTY -> R.string.document_import_empty
            PrivateDocumentImport.Reason.NO_DATA -> R.string.document_import_no_data
            PrivateDocumentImport.Reason.TIMED_OUT -> R.string.document_import_timed_out
            PrivateDocumentImport.Reason.UNSUPPORTED -> R.string.document_import_unsupported
            else -> R.string.document_open_failed
        }
        else -> R.string.document_open_failed
    }

    /** Restore the previously owner-tested one-sheet pilot, without changing either CAPT engine. */
    private fun confirmTestPrint() {
        val source = session ?: return
        if (copying || !previewReady) { status.setText(R.string.document_plan_invalid); return }
        val frozen = runCatching { plan(source) }.getOrElse {
            status.setText(R.string.document_plan_invalid); return
        }
        FirstPdfPrintGate.rejection(source.overview.format, frozen, displayedPage)?.let {
            status.text = it; return
        }
        if (PrintQueueRuntime.hasWork()) { status.setText(R.string.document_test_queue_busy); return }
        val usb = getSystemService(USB_SERVICE) as UsbManager
        val device = usb.deviceList.values.filter {
            UsbDeviceProfiles.identify(it.vendorId, it.productId)?.modelName == "Canon LBP2900"
        }.singleOrNull()
        if (device == null || !usb.hasPermission(device)) { status.setText(R.string.document_no_printer); return }
        val request = generation
        val page = displayedPage
        AlertDialog.Builder(this).setTitle(R.string.document_test_print)
            .setMessage(getString(R.string.document_test_confirm, page + 1))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.document_test_action) { _, _ ->
                if (source !== session || request != generation || page != displayedPage ||
                    !visible || !previewReady || copying || PrintQueueRuntime.hasWork() ||
                    runCatching { plan(source) }.getOrNull() != frozen) return@setPositiveButton
                copying = true; testRunning = true; testCancelled.set(false)
                pick.isEnabled = false; pageRange.isEnabled = false; copies.isEnabled = false
                rotation.isEnabled = false
                for (i in 0 until orientation.childCount) orientation.getChildAt(i).isEnabled = false
                updatePrintActions()
                status.setText(R.string.document_test_preparing)
                worker.execute {
                    val result = runCatching {
                        FirstPdfPrintAttempt.execute(applicationContext, source, page,
                            frozen.contentRotationDegrees, device.deviceName,
                            cancelled = { testCancelled.get() || request != generation }) { stage ->
                            runOnUiThread {
                                if (!isDestroyed && !isFinishing && request == generation) status.text = stage
                            }
                        }
                    }
                    runOnUiThread {
                        if (isDestroyed || isFinishing) return@runOnUiThread
                        copying = false; testRunning = false
                        pick.isEnabled = true; pageRange.isEnabled = true; copies.isEnabled = true
                        rotation.isEnabled = true
                        for (i in 0 until orientation.childCount) orientation.getChildAt(i).isEnabled = true
                        updatePrintActions()
                        status.text = result.getOrElse { getString(R.string.document_test_failed) }
                    }
                }
            }.show()
    }

    private fun confirmPrint() {
        val source = session ?: return
        if (copying || !previewReady) { status.setText(R.string.document_plan_invalid); return }
        val frozen = runCatching { plan(source) }.getOrElse { status.setText(R.string.document_plan_invalid); return }
        if (frozen.pageIndices.first() != displayedPage) { status.setText(R.string.document_preview_first); return }
        val usb = getSystemService(USB_SERVICE) as UsbManager
        val device = usb.deviceList.values.filter {
            UsbDeviceProfiles.identify(it.vendorId, it.productId)?.modelName == "Canon LBP2900"
        }.singleOrNull()
        if (device == null || !usb.hasPermission(device)) { status.setText(R.string.document_no_printer); return }
        val request = generation
        AlertDialog.Builder(this).setTitle(R.string.document_print)
            .setMessage(getString(R.string.document_print_confirm, frozen.outputPageOrder.size,
                frozen.copies, frozen.pageIndices.take(20).joinToString(", ") { (it+1).toString() } +
                    (if (frozen.pageIndices.size > 20) ", …" else ""),
                getString(if (frozen.orientation == Orientation.PORTRAIT) R.string.document_portrait
                    else R.string.document_landscape), frozen.contentRotationDegrees))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.document_print_action) { _, _ ->
                if (source !== session || request != generation || !previewReady || copying ||
                    runCatching { plan(source) }.getOrNull() != frozen) return@setPositiveButton
                // Notification denial does not remove the OS foreground-service requirement.
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 201)
                }
                copying = true; pick.isEnabled = false
                updatePrintActions()
                status.setText(R.string.document_enqueuing)
                getPreferences(MODE_PRIVATE).edit().putInt("copies", frozen.copies)
                    .putInt("orientation", findViewById<RadioButton>(orientation.checkedRadioButtonId)?.tag as? Int ?: 0)
                    .putBoolean("rotation", rotation.isChecked).apply()
                worker.execute {
                    val result = runCatching { PrintQueueRuntime.enqueue(applicationContext, source, frozen, device.deviceName) }
                    runOnUiThread {
                        copying = false
                        updatePrintActions()
                        if (isDestroyed || isFinishing || !visible) {
                            result.getOrNull()?.let { runCatching { PrintQueueRuntime.cancel(it) } }; return@runOnUiThread
                        }
                        pick.isEnabled = true; print.isEnabled = true
                        result.fold({ id ->
                            try {
                                PrintQueueService.start(this)
                                PrintQueueRuntime.activate(id)
                                status.setText(R.string.document_queued)
                                startActivity(Intent(this, PrintQueueActivity::class.java))
                            } catch (_: Exception) {
                                runCatching { PrintQueueRuntime.cancel(id) }; status.setText(R.string.queue_start_failed)
                            }
                        }, { status.text = getString(R.string.document_enqueue_failed, it.javaClass.simpleName) })
                    }
                }
            }.show()
    }
    private fun sharePdfReport() {
        try {
            val source = FirstPdfPrintAttempt.reportFile(this)
            if (!source.isFile) { status.setText(R.string.document_no_report); return }
            val directory = File(cacheDir, "reports").apply { mkdirs() }
            val copy = File.createTempFile("capt-document-", ".json", directory)
            check(source.length() <= 1024 * 1024)
            copy.writeText(source.readText(Charsets.UTF_8), Charsets.UTF_8)
            directory.listFiles()?.sortedByDescending { it.lastModified() }?.drop(24)?.forEach { it.delete() }
            val uri = FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.reportprovider", copy)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"; putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(contentResolver, copy.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, getString(R.string.document_report)))
        } catch (_: Exception) { status.setText(R.string.document_share_failed) }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        sourceUri?.let { outState.putString("uri", it.toString()) }
        outState.putString("pages", pageRange.text.toString()); outState.putString("copies", copies.text.toString())
        outState.putInt("page", displayedPage)
        outState.putInt("orientation", findViewById<RadioButton>(orientation.checkedRadioButtonId)?.tag as? Int ?: 0)
        outState.putBoolean("rotation", rotation.isChecked)
    }
    override fun onDestroy() {
        testCancelled.set(true)
        ++generation; ++previewGeneration
        preview.clearImage(); bitmap?.recycle(); bitmap = null
        val source = session; session = null
        worker.execute { source?.close() }; worker.shutdown()
        super.onDestroy()
    }
}
