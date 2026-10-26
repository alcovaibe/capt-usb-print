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
import com.cryonum.captusbprint.office.OfficeFailure
import com.cryonum.captusbprint.queue.PrintQueueRuntime
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Real native Office -> actual internal UI -> held common queue, no USB activation. */
class OfficeNativeUiSmoke : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        waitForIdleSync()
        var stage="start"; var outcome=Activity.RESULT_CANCELED
        var activity: OfficeFixturePreviewActivity?=null; var held: Long?=null
        val result=Bundle()
        fun <T> ui(action:()->T):T { val value=AtomicReference<T>(); runOnMainSync { value.set(action()) }; return value.get() }
        fun current()=requireNotNull(OfficeFixturePreviewActivity.current.get())
        fun snapshot()=ui { current().snapshot() }
        fun click(tag:String)=ui { current().findViewById<View>(android.R.id.content).findViewWithTag<View>(tag).performClick() }
        fun await(condition:()->Boolean) {
            val end=SystemClock.elapsedRealtime()+120_000
            while(!condition()) { check(SystemClock.elapsedRealtime()<end); Thread.sleep(50) }
        }
        fun folders(name:String)=File(targetContext.noBackupFilesDir,name).listFiles()?.map { it.name }?.toSet() ?: emptySet()
        val sources=folders("office-ui"); val workers=folders("office-work")
        try {
            val reports=JSONObject(File(targetContext.filesDir,"office-worker-results.json").readText()).getJSONArray("conversions")
            fun pages(kind:String,selection:String,fit:Boolean):Int = (0 until reports.length()).map { reports.getJSONObject(it) }
                .single { it.getString("format")==kind && it.getString("selectedSheets")==selection && it.getBoolean("fitWidth")==fit }.getInt("pdfPages")
            for(kind in listOf("docx","doc")) {
                stage="$kind-open"
                activity=startActivitySync(Intent(targetContext,OfficeFixturePreviewActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("kind",kind)) as OfficeFixturePreviewActivity
                await { snapshot().ready && snapshot().pages==3 }
                check(snapshot().sheets.isEmpty() && !snapshot().fit)
                fun navigate(description:Int) = ui {
                    val found=ArrayList<View>()
                    current().findViewById<View>(android.R.id.content).findViewsWithText(found,
                        targetContext.getString(description),View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
                    check(found.single().performClick())
                }
                stage="$kind-navigation"
                navigate(R.string.preview_next); await { snapshot().ready && snapshot().page==1 }
                navigate(R.string.preview_next); await { snapshot().ready && snapshot().page==2 }
                check(ui { current().findViewById<View>(android.R.id.content).findViewWithTag<TextView>("office-geometry").text.toString() }
                    .contains(targetContext.getString(R.string.document_landscape)))
                navigate(R.string.preview_previous); await { snapshot().ready && snapshot().page==1 }
                stage="$kind-range-copies"
                ui {
                    val root=current().findViewById<View>(android.R.id.content)
                    root.findViewWithTag<EditText>("office-range").setText("2-3")
                    root.findViewWithTag<EditText>("office-copies").setText("2")
                }
                click("office-reviewed")
                check(ui { current().findViewById<View>(android.R.id.content).findViewWithTag<View>("office-print").isEnabled })
                val id=AtomicLong(0)
                ui { current().enqueueHeldForSmoke({ id.set(it) },{ error("NATIVE_WORD_HELD_FAILED") }) }
                await { id.get()!=0L }; held=id.get()
                val record=PrintQueueRuntime.snapshot().single { it.id==held }
                check(!record.ready && !record.usbStarted && record.sheets==4)
                val copy=File(targetContext.noBackupFilesDir,"print-queue/$held/source.bin")
                stage="$kind-close"
                ui { current().finish() }
                await { folders("office-ui")==sources && folders("office-work")==workers }
                check(PdfRenderer(ParcelFileDescriptor.open(copy,ParcelFileDescriptor.MODE_READ_ONLY)).use { it.pageCount }==3)
                PrintQueueRuntime.cancel(requireNotNull(held)); held=null
            }
            for(kind in listOf("xls","xlsx")) {
                stage="$kind-open"
                activity=startActivitySync(Intent(targetContext,OfficeFixturePreviewActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("kind",kind)) as OfficeFixturePreviewActivity
                await { snapshot().ready && snapshot().pages==pages(kind,"all",true) }
                check(snapshot().sheets==listOf(0,1,2))
                stage="$kind-wide-fit"
                click("office-sheet-0"); click("office-sheet-2")
                await { snapshot().ready && snapshot().pages==1 }
                check(snapshot().sheets==listOf(1))
                ui { current().findViewById<View>(android.R.id.content).findViewWithTag<EditText>("office-copies").setText("2") }
                click("office-reviewed")
                check(ui { current().findViewById<View>(android.R.id.content).findViewWithTag<View>("office-print").isEnabled })
                val id=AtomicLong(0)
                ui { current().enqueueHeldForSmoke({ id.set(it) },{ error("NATIVE_UI_HELD_FAILED") }) }
                await { id.get()!=0L }; held=id.get()
                val record=PrintQueueRuntime.snapshot().single { it.id==held }
                check(!record.ready && !record.usbStarted && record.sheets==2)
                val copy=File(targetContext.noBackupFilesDir,"print-queue/$held/source.bin")
                fun copiedPages()=PdfRenderer(ParcelFileDescriptor.open(copy,ParcelFileDescriptor.MODE_READ_ONLY)).use { it.pageCount }
                check(copiedPages()==1)
                stage="$kind-fit-disabled"
                click("office-fit")
                await { snapshot().ready && snapshot().pages==3 }
                check(!ui { current().findViewById<View>(android.R.id.content).findViewWithTag<CheckBox>("office-reviewed").isChecked })
                check(copiedPages()==1)
                stage="$kind-close"
                ui { current().finish() }
                await { folders("office-ui")==sources && folders("office-work")==workers }
                check(copiedPages()==1) // Closing the entire UI still cannot delete the queue's source.
                PrintQueueRuntime.cancel(requireNotNull(held)); held=null
            }
            for(kind in listOf("xls","xlsx")) {
                stage="$kind-edge-discovery"
                activity=startActivitySync(Intent(targetContext,OfficeFixturePreviewActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("kind",kind).putExtra("edge",true)) as OfficeFixturePreviewActivity
                await { snapshot().failure==OfficeFailure.FIT_WIDTH_UNSUPPORTED && !snapshot().loading }
                check(snapshot().sheetCount==6 && snapshot().sheets==(0..5).toList())
                check(snapshot().pages==0 && !snapshot().ready)
                check(!ui { current().findViewById<View>(android.R.id.content).findViewWithTag<View>("office-print").isEnabled })
                check(PrintQueueRuntime.snapshot().none { !it.phase.terminal })
                stage="$kind-edge-good-sheet"
                for(index in listOf(0,1,2,3,5)) click("office-sheet-$index")
                await { snapshot().ready && snapshot().pages==1 }
                check(snapshot().sheets==listOf(4) && snapshot().fit)
                stage="$kind-edge-all-fails-list-survives"
                click("office-all")
                await { snapshot().failure==OfficeFailure.FIT_WIDTH_UNSUPPORTED && !snapshot().loading }
                check(snapshot().sheetCount==6 && !snapshot().ready)
                for(index in listOf(5,3,1,0)) click("office-sheet-$index")
                await { snapshot().ready && snapshot().pages>1 }
                check(snapshot().sheets==listOf(2,4) && snapshot().fit)
                val count=snapshot().pages
                click("office-reviewed")
                val id=AtomicLong(0)
                ui { current().enqueueHeldForSmoke({ id.set(it) },{ error("EDGE_UI_HELD_FAILED") }) }
                await { id.get()!=0L }; held=id.get()
                val record=PrintQueueRuntime.snapshot().single { it.id==held }
                check(!record.ready && !record.usbStarted && record.sheets==count)
                val copy=File(targetContext.noBackupFilesDir,"print-queue/$held/source.bin")
                check(PdfRenderer(ParcelFileDescriptor.open(copy,ParcelFileDescriptor.MODE_READ_ONLY)).use { it.pageCount }==count)
                ui { current().finish() }
                await { folders("office-ui")==sources && folders("office-work")==workers }
                check(copy.isFile)
                PrintQueueRuntime.cancel(requireNotNull(held)); held=null
            }
            check(PrintQueueRuntime.snapshot().none { !it.phase.terminal })
            result.putString("stream","CAPT_OFFICE_NATIVE_UI_PASSED (DOCX/DOC navigation/range/copies; XLS/XLSX discovery before rejected all-sheet Fit, safe subset recovery; held copies/cleanup; no USB)")
            outcome=Activity.RESULT_OK
        } catch(error:Throwable) {
            val state=runCatching { snapshot() }.getOrNull()
            result.putString("stream","OFFICE_NATIVE_UI_FAILED:$stage:${error.javaClass.simpleName}:ready=${state?.ready}:pages=${state?.pages}:loading=${state?.loading}:failure=${state?.failure?.name}")
        } finally {
            held?.let { PrintQueueRuntime.cancel(it) }
            ui { activity?.takeIf { !it.isDestroyed }?.finish() }
        }
        finish(outcome,result)
    }
}
