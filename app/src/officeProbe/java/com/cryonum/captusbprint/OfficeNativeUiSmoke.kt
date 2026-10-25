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
            result.putString("stream","CAPT_OFFICE_NATIVE_UI_PASSED (XLS/XLSX native preview/sheet selection/fit/held copy; no USB)")
            outcome=Activity.RESULT_OK
        } catch(error:Throwable) {
            result.putString("stream","OFFICE_NATIVE_UI_FAILED:$stage:${error.javaClass.simpleName}")
        } finally {
            held?.let { PrintQueueRuntime.cancel(it) }
            ui { activity?.takeIf { !it.isDestroyed }?.finish() }
        }
        finish(outcome,result)
    }
}
