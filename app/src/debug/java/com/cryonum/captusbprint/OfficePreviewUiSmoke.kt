package com.cryonum.captusbprint

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import com.cryonum.captusbprint.office.OfficePreviewActivity
import com.cryonum.captusbprint.office.OfficeFailure
import com.cryonum.captusbprint.queue.PrintQueueRuntime
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Real Android UI/process/copy lifecycle with a fake renderer. NEVER activates a queue job/USB. */
class OfficePreviewUiSmoke : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        waitForIdleSync()
        var stage = "start"
        var activity: OfficePreviewTestActivity? = null
        var held: Long? = null
        var resultCode = Activity.RESULT_CANCELED
        val result = Bundle()
        fun <T> ui(action: () -> T): T {
            val value = AtomicReference<T>()
            runOnMainSync { value.set(action()) }
            return value.get()
        }
        fun await(condition: () -> Boolean) {
            val until = SystemClock.elapsedRealtime()+30_000
            while (!condition()) { check(SystemClock.elapsedRealtime()<until); Thread.sleep(25) }
        }
        fun current() = requireNotNull(OfficePreviewTestActivity.current.get())
        fun snapshot(): OfficePreviewActivity.Snapshot = ui { current().snapshot() }
        fun click(tag: String) = ui { current().findViewById<View>(android.R.id.content).findViewWithTag<View>(tag).performClick() }
        fun checkBox(tag: String): Boolean = ui { current().findViewById<View>(android.R.id.content).findViewWithTag<CheckBox>(tag).isChecked }
        fun files(name: String) = File(targetContext.noBackupFilesDir,name).listFiles()?.map { it.name }?.toSet() ?: emptySet()
        val sources = files("office-ui"); val work = files("office-work")
        val previewFiles = File(targetContext.cacheDir,"stage-c-inspection").listFiles()?.map { it.name }?.toSet() ?: emptySet()
        try {
            stage = "open"
            activity = startActivitySync(Intent(targetContext,OfficePreviewTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("kind","xls")) as OfficePreviewTestActivity
            await { snapshot().ready && snapshot().pages==6 }
            check(snapshot().sheets==listOf(0,1,2) && snapshot().fit)
            stage = "latest-subset"
            val old = snapshot().version
            click("office-sheet-0"); click("office-sheet-1")
            await { snapshot().ready && snapshot().pages==4 }
            check(snapshot().version>old && snapshot().sheets==listOf(2))
            stage = "range-copies-review"
            ui {
                val root = current().findViewById<View>(android.R.id.content)
                root.findViewWithTag<EditText>("office-range").setText("2-3")
                root.findViewWithTag<EditText>("office-copies").setText("2")
            }
            await { snapshot().ready && snapshot().page==1 }
            click("office-reviewed"); check(checkBox("office-reviewed"))
            val queued = AtomicLong(0)
            ui { current().enqueueHeldForSmoke({ queued.set(it) }, { error("HELD_ENQUEUE_FAILED") }) }
            await { queued.get()!=0L }; held=queued.get()
            val record = PrintQueueRuntime.snapshot().single { it.id==held }
            check(!record.ready && !record.usbStarted && record.sheets==4)
            val copy = File(targetContext.noBackupFilesDir,"print-queue/$held/source.bin")
            fun copiedPages() = PdfRenderer(ParcelFileDescriptor.open(copy,ParcelFileDescriptor.MODE_READ_ONLY)).use { it.pageCount }
            check(copiedPages()==4)
            stage = "all-and-fit-off"
            click("office-all"); click("office-fit")
            await { snapshot().ready && snapshot().pages==10 }
            check(snapshot().sheets==listOf(0,1,2) && !snapshot().fit && !checkBox("office-reviewed"))
            check(copiedPages()==4) // The queue still owns the reviewed earlier PDF snapshot.
            PrintQueueRuntime.cancel(requireNotNull(held)); held=null
            stage = "empty-selection"
            for (index in 0..2) click("office-sheet-$index")
            await { snapshot().pages==0 && !snapshot().ready }
            click("office-all"); await { snapshot().ready && snapshot().pages==10 }
            stage = "cancel-and-retry"
            click("office-fit"); click("office-cancel")
            await { ui { current().findViewById<View>(android.R.id.content).findViewWithTag<TextView>("office-status").text.toString() } == targetContext.getString(R.string.document_import_cancelled) }
            click("office-retry"); await { snapshot().ready && snapshot().pages==6 }
            stage = "recreate"
            click("office-sheet-0"); click("office-sheet-1")
            await { snapshot().ready && snapshot().pages==4 }
            val previous = current()
            ui { previous.recreate() }
            await { current()!==previous && snapshot().ready && snapshot().pages==4 }
            activity=current()
            check(snapshot().sheets==listOf(2) && snapshot().fit && !checkBox("office-reviewed"))
            stage = "close-while-preparing"
            click("office-fit"); ui { current().finish() }
            await { files("office-ui")==sources && files("office-work")==work }
            check((File(targetContext.cacheDir,"stage-c-inspection").listFiles()?.map { it.name }?.toSet() ?: emptySet())==previewFiles)
            stage = "private-failure"
            activity=startActivitySync(Intent(targetContext,OfficePreviewTestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("sourceFailure",true)) as OfficePreviewTestActivity
            await { ui { current().findViewById<View>(android.R.id.content).findViewWithTag<TextView>("office-status").text.toString() } == targetContext.getString(R.string.office_error_failed) }
            ui { current().finish() }
            await { files("office-ui")==sources }
            stage = "cancel-sheet-discovery"
            activity=startActivitySync(Intent(targetContext,OfficePreviewTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("rejectedSheet",true)) as OfficePreviewTestActivity
            await { File(targetContext.noBackupFilesDir,"office-work").listFiles()?.any {
                File(it,"sheet-scan-started").isFile } == true }
            check(snapshot().loading && snapshot().sheetCount==0 && !snapshot().ready)
            click("office-cancel")
            await { !snapshot().loading && snapshot().sheetCount==0 }
            await { files("office-work")==work }
            check(snapshot().pages==0 && !snapshot().ready)
            stage = "discover-before-rejected-layout"
            click("office-retry")
            await { snapshot().failure==OfficeFailure.FIT_WIDTH_UNSUPPORTED && !snapshot().loading }
            check(snapshot().sheetCount==3 && snapshot().sheets==listOf(0,1,2))
            check(snapshot().pages==0 && !snapshot().ready)
            check(!ui { current().findViewById<View>(android.R.id.content).findViewWithTag<View>("office-print").isEnabled })
            check(PrintQueueRuntime.snapshot().none { !it.phase.terminal })
            stage = "select-after-rejected-layout"
            click("office-sheet-0")
            await { snapshot().ready && snapshot().pages==5 }
            check(snapshot().sheets==listOf(1,2) && snapshot().sheetCount==3 && snapshot().fit)
            click("office-all")
            await { snapshot().failure==OfficeFailure.FIT_WIDTH_UNSUPPORTED && !snapshot().loading }
            check(snapshot().sheetCount==3 && !snapshot().ready)
            click("office-fit")
            await { snapshot().ready && snapshot().pages==10 }
            ui { current().finish() }
            await { files("office-ui")==sources && files("office-work")==work }
            check(PrintQueueRuntime.snapshot().none { !it.phase.terminal })
            result.putString("stream","CAPT_OFFICE_PREVIEW_UI_PASSED (fake renderer; independent sheets/cancel/rejected layout recovery/fit/held copy/recreate/cleanup; no USB)")
            resultCode=Activity.RESULT_OK
        } catch (error: Throwable) {
            val state = runCatching { snapshot() }.getOrNull()
            result.putString("stream","OFFICE_PREVIEW_UI_FAILED:$stage:${error.javaClass.simpleName}:ready=${state?.ready}:pages=${state?.pages}:loading=${state?.loading}:failure=${state?.failure?.name}")
        } finally {
            held?.let { PrintQueueRuntime.cancel(it) }
            ui { activity?.takeIf { !it.isDestroyed }?.finish() }
        }
        finish(resultCode,result)
    }
}
