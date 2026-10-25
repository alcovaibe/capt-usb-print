package com.cryonum.captusbprint.office

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.cryonum.captusbprint.DocumentPreviewPanel
import com.cryonum.captusbprint.R
import com.cryonum.captusbprint.core.JobSpecification
import com.cryonum.captusbprint.core.Orientation
import com.cryonum.captusbprint.queue.PrintQueueActivity
import com.cryonum.captusbprint.queue.PrintQueueRuntime
import com.cryonum.captusbprint.queue.PrintQueueService
import com.cryonum.captusbprint.usb.UsbDeviceProfiles
import java.io.File
import java.io.InputStream
import java.util.UUID

/**
 * Internal Office workflow. No manifest/URI entry in ordinary builds.
 * Debug/probe subclasses provide ONLY fixed synthetic assets; no Intent file paths or URIs.
 * User intake must remain gated until the engine resource policy/license audit are complete.
 */
internal abstract class OfficePreviewActivity : Activity() {
    protected abstract val officeKind: OfficeKind
    protected abstract fun openSource(): InputStream
    protected open val service: Class<out OfficeWorkerService> get() = OfficeWorkerService::class.java
    protected open val allowQueue: Boolean get() = false
    private lateinit var preparation: OfficePreviewPreparation
    private lateinit var preview: DocumentPreviewPanel
    private lateinit var message: TextView
    private lateinit var geometry: TextView
    private lateinit var sheetRows: LinearLayout
    private lateinit var fit: CheckBox
    private lateinit var acknowledge: CheckBox
    private lateinit var range: EditText
    private lateinit var copies: EditText
    private lateinit var print: Button
    private lateinit var cancel: Button
    private lateinit var retry: Button
    private var bitmap: Bitmap? = null
    private var metadata: OfficePreviewMetadata? = null
    private var lastFailure: OfficeFailure? = null
    private var names = emptyList<String>()
    private val selected = sortedSetOf<Int>()
    private var initialAll = true
    private var token = 0L
    @Volatile private var renderToken = 0L
    private var page = 0
    private var previewReady = false
    private var loading = false
    private var enqueuing = false
    private var visible = false
    private var muted = false
    private var restored: Bundle? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restored = savedInstanceState
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,24,24,24) }
        fun text(res: Int) = TextView(this).apply { setText(res); textSize = 16f; body.addView(this) }
        text(R.string.office_title)
        text(R.string.office_internal_fixture)
        message = text(R.string.document_loading).apply { tag = "office-status" }
        sheetRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "office-sheets" }
        body.addView(sheetRows)
        body.addView(Button(this).apply {
            setText(R.string.office_all_sheets); tag = "office-all"
            visibility = if (officeKind.spreadsheet) View.VISIBLE else View.GONE
            setOnClickListener {
                if (enqueuing) return@setOnClickListener
                selected.clear(); selected.addAll(names.indices); initialAll = true
                syncChecks(); reprepare()
            }
        })
        fit = CheckBox(this).apply {
            setText(R.string.office_fit_width); tag = "office-fit"
            visibility = if (officeKind.spreadsheet) View.VISIBLE else View.GONE
            isChecked = officeKind.spreadsheet && (savedInstanceState?.getBoolean("fit", true) ?: true)
            setOnCheckedChangeListener { _, _ -> if (!muted) reprepare() }
        }
        body.addView(fit)
        cancel = Button(this).apply {
            setText(R.string.document_cancel_import); tag = "office-cancel"
            setOnClickListener { if (!enqueuing) { preparation.cancel(); invalidatePreview(); updateActions() } }
        }
        body.addView(cancel)
        retry = Button(this).apply { setText(R.string.office_retry); tag = "office-retry"; setOnClickListener { reprepare() } }
        body.addView(retry)
        preview = DocumentPreviewPanel(this).apply {
            tag = "office-preview"
            onPrevious = { render(page - 1) }; onNext = { render(page + 1) }
        }
        body.addView(preview)
        geometry = text(R.string.office_original_layout).apply { tag = "office-geometry" }
        text(R.string.document_pages)
        range = EditText(this).apply { setSingleLine(true); hint = getString(R.string.document_all_pages); tag = "office-range" }
        body.addView(range)
        text(R.string.document_copies)
        copies = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER; setSingleLine(true); tag = "office-copies"
            setText(savedInstanceState?.getString("copies") ?: "1")
        }
        body.addView(copies)
        text(R.string.office_compatibility)
        acknowledge = CheckBox(this).apply {
            setText(R.string.office_reviewed); tag = "office-reviewed"
            setOnCheckedChangeListener { _, _ -> updateActions() }
        }
        body.addView(acknowledge)
        print = Button(this).apply { setText(R.string.document_print); tag = "office-print"; setOnClickListener { confirmPrint() } }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(ScrollView(this@OfficePreviewActivity).apply { addView(body) }, LinearLayout.LayoutParams(-1,0,1f))
            addView(print)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left,safe.top,safe.right,safe.bottom); insets
        }
        setContentView(root); ViewCompat.requestApplyInsets(root)
        val source = File(noBackupFilesDir, "office-ui/${UUID.randomUUID()}")
        preparation = OfficePreviewPreparation(this, officeKind, source, { openSource() }, service) { state ->
            if (isFinishing || isDestroyed) return@OfficePreviewPreparation
            token = state.version
            loading = state.phase == LatestPreparation.Phase.LOADING
            when (state.phase) {
                LatestPreparation.Phase.LOADING -> {
                    invalidatePreview(); metadata = null
                    preview.showDocument(); preview.setLoading(); message.setText(R.string.document_loading)
                }
                LatestPreparation.Phase.READY -> {
                    val next = requireNotNull(state.metadata); metadata = next
                    if (names != next.sheets) {
                        names = next.sheets
                        if (initialAll) { selected.clear(); selected.addAll(names.indices) }
                        buildSheetRows()
                    }
                    message.text = getString(R.string.office_ready, next.pageCount)
                    val saved = restored; restored = null
                    muted = true; range.setText(saved?.getString("range") ?: ""); muted = false
                    render((saved?.getInt("page") ?: 0).coerceIn(0,next.pageCount-1))
                }
                LatestPreparation.Phase.CANCELLED -> {
                    metadata = null; invalidatePreview()
                    if (officeKind.spreadsheet && !initialAll && selected.isEmpty()) {
                        message.setText(R.string.office_select_sheet); preview.showError(message.text.toString())
                    } else showFailure(OfficeFailure.CANCELLED)
                }
                LatestPreparation.Phase.FAILED -> { metadata = null; invalidatePreview(); showFailure(requireNotNull(state.failure)) }
            }
            updateActions()
        }
        savedInstanceState?.getIntArray("sheets")?.let {
            initialAll = false; selected.addAll(it.filter { index -> index in 0..255 })
        }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!muted) {
                    acknowledge.isChecked = false
                    val first = runCatching { plan().pageIndices.first() }.getOrNull()
                    if (first != null && first != page) render(first)
                    updateActions()
                }
            }
            override fun afterTextChanged(s: Editable?) = Unit
        }
        range.addTextChangedListener(watcher); copies.addTextChangedListener(watcher)
        reprepare(clearRange = false)
    }
    private fun invalidatePreview() {
        ++renderToken; previewReady = false
        preview.clearImage(); bitmap?.recycle(); bitmap = null
        if (::acknowledge.isInitialized) acknowledge.isChecked = false
    }
    private fun buildSheetRows() {
        sheetRows.removeAllViews()
        names.forEachIndexed { index, name ->
            sheetRows.addView(CheckBox(this).apply {
                text = name; tag = "office-sheet-$index"; isChecked = index in selected
                setOnCheckedChangeListener { _, checked ->
                    if (!muted && !enqueuing) {
                        initialAll = false
                        if (checked) selected.add(index) else selected.remove(index)
                        reprepare()
                    }
                }
            })
        }
    }
    private fun syncChecks() {
        muted = true
        for (i in 0 until sheetRows.childCount) (sheetRows.getChildAt(i) as CheckBox).isChecked = i in selected
        muted = false
    }
    private fun reprepare(clearRange: Boolean = true) {
        if (!::preparation.isInitialized || enqueuing) return
        invalidatePreview(); metadata = null; lastFailure = null; loading = true
        if (clearRange) { muted = true; range.setText(""); muted = false; page = 0; restored = null }
        if (officeKind.spreadsheet && !initialAll && selected.isEmpty()) {
            preparation.cancel(); token = preparation.version; loading = false
            message.setText(R.string.office_select_sheet); preview.showError(getString(R.string.office_select_sheet))
            updateActions(); return
        }
        token = preparation.prepare(if (officeKind.spreadsheet && !initialAll) selected.toList() else null, fit.isChecked)
        preview.showDocument(); preview.setLoading(); updateActions()
    }
    private fun render(index: Int) {
        val meta = metadata ?: return
        if (enqueuing || index !in 0 until meta.pageCount) return
        val draw = ++renderToken; val version = token
        previewReady = false; preview.setLoading(); updateActions()
        preparation.render(version,index,{ draw != renderToken }, { next ->
            if (draw != renderToken || version != token || isDestroyed || isFinishing) { next.recycle(); return@render }
            preview.clearImage(); bitmap?.recycle(); bitmap = next
            page = index; previewReady = true
            preview.showBitmap(next,index,meta.pageCount)
            geometry.text = getString(R.string.office_page_geometry, if (meta.orientations[index] == Orientation.PORTRAIT)
                getString(R.string.document_portrait) else getString(R.string.document_landscape))
            updateActions()
        }, { code -> if (draw == renderToken) { showFailure(code); updateActions() } })
    }
    private fun plan(): JobSpecification {
        val meta = requireNotNull(metadata)
        return JobSpecification.preparedPdf(meta.orientations, range.text.toString(), copies.text.toString().trim().toInt())
    }
    private fun updateActions() {
        if (!::print.isInitialized) return
        val valid = runCatching { plan() }.getOrNull()
        print.isEnabled = allowQueue && visible && previewReady && acknowledge.isChecked && !enqueuing && valid?.pageIndices?.first() == page
        cancel.visibility = if (loading) View.VISIBLE else View.GONE
        cancel.isEnabled = !enqueuing
        retry.visibility = if (!loading && metadata == null) View.VISIBLE else View.GONE
        fit.isEnabled = !enqueuing
        range.isEnabled = !enqueuing; copies.isEnabled = !enqueuing; acknowledge.isEnabled = !enqueuing
        for (i in 0 until sheetRows.childCount) sheetRows.getChildAt(i).isEnabled = !enqueuing
    }
    private fun confirmPrint() {
        if (!allowQueue || !visible || !previewReady || !acknowledge.isChecked || enqueuing) return
        val frozen = runCatching { plan() }.getOrNull() ?: return
        if (frozen.pageIndices.first() != page) return
        val manager = getSystemService(USB_SERVICE) as UsbManager
        val device = manager.deviceList.values.filter {
            UsbDeviceProfiles.identify(it.vendorId,it.productId)?.modelName == "Canon LBP2900"
        }.singleOrNull()
        if (device == null || !manager.hasPermission(device)) { message.setText(R.string.document_no_printer); return }
        val version = token
        AlertDialog.Builder(this).setTitle(R.string.document_print)
            .setMessage(getString(R.string.office_print_confirm,frozen.outputPageOrder.size,frozen.copies))
            .setNegativeButton(android.R.string.cancel,null)
            .setPositiveButton(R.string.document_print_action) { _, _ ->
                if (!visible || version != token || !previewReady || !acknowledge.isChecked || runCatching { plan() }.getOrNull() != frozen) return@setPositiveButton
                enqueuing = true; updateActions()
                preparation.enqueueHeld(version,range.text.toString(),frozen.copies,device.deviceName, { id ->
                    enqueuing = false
                    if (isFinishing || isDestroyed || !visible || version != token) { PrintQueueRuntime.cancel(id); return@enqueueHeld }
                    try {
                        PrintQueueService.start(this); PrintQueueRuntime.activate(id)
                        startActivity(Intent(this,PrintQueueActivity::class.java))
                    } catch (_: Exception) { PrintQueueRuntime.cancel(id); message.setText(R.string.queue_start_failed) }
                    updateActions()
                }, { showFailure(it); enqueuing = false; updateActions() })
            }.show()
    }
    private fun showFailure(code: OfficeFailure) { lastFailure = code; message.setText(OfficePreviewMessages.resource(code)); preview.showError(message.text.toString()) }
    override fun onStart() { super.onStart(); visible = true; updateActions() }
    override fun onStop() { visible = false; super.onStop() }
    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        if (!initialAll) out.putIntArray("sheets",selected.toIntArray())
        out.putBoolean("fit",fit.isChecked); out.putString("range",range.text.toString())
        out.putString("copies",copies.text.toString()); out.putInt("page",page)
    }
    override fun onDestroy() {
        invalidatePreview()
        if (::preparation.isInitialized) preparation.close()
        super.onDestroy()
    }
    // Instrumentation can exercise the same held handoff without finding a USB printer.
    internal fun enqueueHeldForSmoke(success: (Long) -> Unit, failure: (OfficeFailure) -> Unit) {
        check(previewReady && acknowledge.isChecked && plan().pageIndices.first() == page)
        preparation.enqueueHeld(token,range.text.toString(),plan().copies,"offline-office-ui-no-usb",success,failure)
    }
    internal data class Snapshot(val version: Long,val ready: Boolean,val page: Int,val pages: Int,val sheets: List<Int>,val fit: Boolean,val failure: OfficeFailure?,val loading: Boolean)
    internal fun snapshot() = Snapshot(token,previewReady,page,metadata?.pageCount ?: 0,selected.toList(),fit.isChecked,lastFailure,loading)
}
