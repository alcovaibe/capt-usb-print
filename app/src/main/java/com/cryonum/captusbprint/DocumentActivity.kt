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
import com.cryonum.captusbprint.queue.PrintQueueActivity
import com.cryonum.captusbprint.queue.PrintQueueRuntime
import com.cryonum.captusbprint.queue.PrintQueueService
import com.cryonum.captusbprint.usb.UsbDeviceProfiles
import java.io.File
import java.util.concurrent.Executors

/** UI owns a preview source; only the foreground queue owns printing sources/jobs. */
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
    private var session: DocumentSession? = null
    private var bitmap: Bitmap? = null
    private var sourceUri: Uri? = null
    private var displayedPage = 0
    @Volatile private var generation = 0
    @Volatile private var previewGeneration = 0
    private var previewReady = false
    private var copying = false
    private var visible = false
    private var restoring: Bundle? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoring = savedInstanceState
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,24,24,24) }
        fun label(res: Int) { layout.addView(TextView(this).apply { setText(res); textSize = 16f }) }
        label(R.string.document_title)
        label(R.string.document_description)
        pick = Button(this).apply {
            setText(R.string.document_pick)
            setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE)
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/pdf", "image/jpeg", "image/png"))
                }
                runCatching { @Suppress("DEPRECATION") startActivityForResult(intent, 101) }
                    .onFailure { message.setText(R.string.document_open_failed) }
            }
        }
        layout.addView(pick)
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
        layout.addView(print)
        layout.addView(Button(this).apply {
            setText(R.string.document_report)
            setOnClickListener { sharePdfReport() }
        })
        val scroll = ScrollView(this).apply { addView(layout) }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom); insets
        }
        setContentView(scroll)
        ViewCompat.requestApplyInsets(scroll)
        val restored = savedInstanceState?.getString("uri")?.let { Uri.parse(it) }
        val shared = if (intent?.action == Intent.ACTION_SEND) {
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else { @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) }
        } else null
        (restored ?: shared ?: intent?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri)?.let { inspect(it) }
    }
    override fun onStart() { super.onStart(); visible = true }
    override fun onStop() { visible = false; super.onStop() }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 101 && resultCode == RESULT_OK) data?.data?.let {
            runCatching { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
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
        preview.clearImage(); bitmap?.recycle(); bitmap = null
        preview.setLoading(); message.setText(R.string.document_loading)
        worker.execute {
            old?.close()
            val result = runCatching { DocumentInspector.openSession(applicationContext, uri) }
            runOnUiThread {
                if (isDestroyed || isFinishing || request != generation) {
                    result.getOrNull()?.close(); return@runOnUiThread
                }
                result.fold({ source ->
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
                }, { message.setText(if (it is SecurityException) R.string.document_access_failed
                                     else R.string.document_open_failed) })
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
            }
        }
    }
    private fun plan(source: DocumentSession) = JobSpecification.create(source.overview.pageCount,
        pageRange.text.toString(), copies.text.toString().trim().toInt(), selectedOrientation(),
        contentRotationDegrees = if (rotation.isChecked) 90 else 0)

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
                copying = true; pick.isEnabled = false; print.isEnabled = false
                status.setText(R.string.document_enqueuing)
                getPreferences(MODE_PRIVATE).edit().putInt("copies", frozen.copies)
                    .putInt("orientation", findViewById<RadioButton>(orientation.checkedRadioButtonId)?.tag as? Int ?: 0)
                    .putBoolean("rotation", rotation.isChecked).apply()
                worker.execute {
                    val result = runCatching { PrintQueueRuntime.enqueue(applicationContext, source, frozen, device.deviceName) }
                    runOnUiThread {
                        copying = false
                        if (isDestroyed || isFinishing || !visible) {
                            result.getOrNull()?.let { PrintQueueRuntime.cancel(it) }; return@runOnUiThread
                        }
                        pick.isEnabled = true; print.isEnabled = true
                        result.fold({ id ->
                            try {
                                PrintQueueService.start(this)
                                status.setText(R.string.document_queued)
                                startActivity(Intent(this, PrintQueueActivity::class.java))
                            } catch (_: Exception) {
                                PrintQueueRuntime.cancel(id); status.setText(R.string.queue_start_failed)
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
        ++generation; ++previewGeneration
        preview.clearImage(); bitmap?.recycle(); bitmap = null
        val source = session; session = null
        worker.execute { source?.close() }; worker.shutdown()
        super.onDestroy()
    }
}
