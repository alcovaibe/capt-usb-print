package com.cryonum.captusbprint

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import com.cryonum.captusbprint.office.*
import com.cryonum.captusbprint.queue.PrintQueueRuntime
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Ordinary production route; separate-UID provider, genuine renderer, no USB activation. */
class OfficeUserWorkflowSmoke : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        waitForIdleSync()
        var stage = "start"
        var code = Activity.RESULT_CANCELED
        val result = Bundle()
        var intake: Activity? = null
        var office: OfficeDocumentActivity? = null
        var held: Long? = null
        val authority = Uri.parse("content://com.cryonum.captusbprint.urifixture")
        fun control(method: String, uri: Uri? = null) = requireNotNull(targetContext.contentResolver.call(authority,method,uri?.toString(),null))
        fun <T> ui(action: () -> T): T {
            val value = AtomicReference<T>(); runOnMainSync { value.set(action()) }; return value.get()
        }
        fun await(condition: () -> Boolean) {
            val until = SystemClock.elapsedRealtime()+120_000
            while (!condition()) { check(SystemClock.elapsedRealtime()<until); Thread.sleep(40) }
        }
        fun folders(name: String) = File(targetContext.noBackupFilesDir,name).listFiles()?.map { it.name }?.toSet() ?: emptySet()
        val sources = folders("office-ui"); val workers = folders("office-work")
        val grants = targetContext.contentResolver.persistedUriPermissions.map { it.uri to it.isReadPermission }
        val monitor = addMonitor(OfficeDocumentActivity::class.java.name,null,false)
        val uris = mutableListOf<Uri>()
        try {
            check(control("state").getInt("uid") != android.os.Process.myUid())
            for ((index,kind) in OfficeKind.entries.withIndex()) {
                stage = "${kind.extension}-intake"
                // Provider reports image/png; one DOCX also deliberately has a .xls name.
                val name = if (kind == OfficeKind.DOCX) "misleading.xls"
                    else "${if (kind.spreadsheet) "workbook" else "word"}.${kind.extension}"
                val uri = Uri.withAppendedPath(authority,name); uris.add(uri); control("grant",uri)
                if (index % 2 == 0) {
                    intake = startActivitySync(Intent(targetContext,DocumentActivity::class.java)
                        .setAction(Intent.ACTION_SEND).setType("application/pdf")
                        .putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } else {
                    intake = startActivitySync(Intent(targetContext,DocumentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    // Intercept only the platform picker UI. Exercise the real button, MIME hints,
                    // onActivityResult and content grant using the independent provider below.
                    val picker = addMonitor(IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addDataType("*/*"); addCategory(Intent.CATEGORY_OPENABLE)
                    },
                        ActivityResult(Activity.RESULT_OK,Intent().setData(uri)),true)
                    ui {
                        val matches = ArrayList<View>()
                        intake!!.findViewById<View>(android.R.id.content).findViewsWithText(matches,
                            targetContext.getString(R.string.document_pick),View.FIND_VIEWS_WITH_TEXT)
                        check(matches.single().performClick())
                    }
                    removeMonitor(picker)
                }
                office = waitForMonitorWithTimeout(monitor,30_000) as OfficeDocumentActivity
                fun current() = requireNotNull(office)
                fun state() = ui { current().snapshot() }
                fun click(tag: String) = ui { current().findViewById<View>(android.R.id.content).findViewWithTag<View>(tag).performClick() }
                stage = "${kind.extension}-preview"
                await { state().ready || state().failure != null }
                check(state().ready) { state().failure?.name ?: "NO_PREVIEW" }
                check(state().pages == if (kind.spreadsheet) 8 else 3)
                check(state().sheetCount == if (kind.spreadsheet) 3 else 0)
                control("revoke",uri)
                val opens = control("state").getInt("opens")
                if (kind.spreadsheet) {
                    stage = "${kind.extension}-subset"
                    click("office-sheet-0"); click("office-sheet-2")
                    await { state().ready && state().pages == 1 }
                    click("office-fit"); await { state().ready && state().pages == 3 }
                }
                stage = "${kind.extension}-recreate-without-grant"
                ui {
                    val root = current().findViewById<View>(android.R.id.content)
                    root.findViewWithTag<EditText>("office-range").setText("2-3")
                    root.findViewWithTag<EditText>("office-copies").setText("2")
                }
                await { state().ready && state().page == 1 }
                ui { current().recreate() }
                office = waitForMonitorWithTimeout(monitor,15_000) as OfficeDocumentActivity
                await { state().ready || state().failure != null }
                check(state().ready && state().pages == 3 && state().page == 1)
                if (kind.spreadsheet) check(state().sheets == listOf(1) && !state().fit)
                check(control("state").getInt("opens") == opens)
                stage = "${kind.extension}-held"
                click("office-reviewed")
                check(ui { current().findViewById<View>(android.R.id.content).findViewWithTag<CheckBox>("office-reviewed").isChecked })
                val id = AtomicLong()
                ui { current().enqueueHeldForSmoke({ id.set(it) }, { error(it.name) }) }
                await { id.get() != 0L }; held = id.get()
                val record = PrintQueueRuntime.snapshot().single { it.id == held }
                check(!record.ready && !record.usbStarted && record.sheets == 4)
                ui { current().finish(); intake!!.finish() }; office = null; intake = null
                await { folders("office-ui") == sources && folders("office-work") == workers }
                check(File(targetContext.noBackupFilesDir,"print-queue/$held/source.bin").isFile)
                PrintQueueRuntime.cancel(requireNotNull(held)); held = null
                check(targetContext.contentResolver.persistedUriPermissions.map { it.uri to it.isReadPermission } == grants)
            }
            for ((extension,format) in listOf("pdf" to com.cryonum.captusbprint.document.DocumentFormat.PDF,
                "png" to com.cryonum.captusbprint.document.DocumentFormat.PNG,
                "jpg" to com.cryonum.captusbprint.document.DocumentFormat.JPEG)) {
                stage = "ordinary-$extension-through-same-intake"
                val uri = Uri.withAppendedPath(authority,"standard.$extension"); uris.add(uri); control("grant",uri)
                intake = startActivitySync(Intent(targetContext,DocumentActivity::class.java)
                    .setAction(Intent.ACTION_SEND).setType("application/msword").putExtra(Intent.EXTRA_STREAM,uri)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                await { ui { (intake as DocumentActivity).intakeSnapshot().ready } }
                check(ui { (intake as DocumentActivity).intakeSnapshot().format } == format)
                ui { intake!!.finish() }; intake = null
                await { folders("office-work") == workers }
            }
            stage = "expired-private-source-after-restart"
            val beforeExpired = PrintQueueRuntime.snapshot()
            office = startActivitySync(Intent(targetContext,OfficeDocumentActivity::class.java)
                .putExtra("office-source",java.util.UUID.randomUUID().toString())
                .putExtra("office-kind","DOCX").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as OfficeDocumentActivity
            check(PrintQueueRuntime.snapshot() == beforeExpired && folders("office-ui") == sources)
            ui { office!!.finish() }; office = null
            stage = "cancel-blocked-intake"
            val uri = Uri.withAppendedPath(authority,"read-block"); uris.add(uri); control("grant",uri)
            val opens = control("state").getInt("opens")
            intake = startActivitySync(Intent(targetContext,DocumentActivity::class.java)
                .setAction(Intent.ACTION_SEND).setType("application/msword").putExtra(Intent.EXTRA_STREAM,uri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            await { control("state").getInt("opens") > opens }
            ui {
                val matches = ArrayList<View>()
                intake!!.findViewById<View>(android.R.id.content).findViewsWithText(matches,
                    targetContext.getString(R.string.document_cancel_import),View.FIND_VIEWS_WITH_TEXT)
                check(matches.single().performClick())
            }
            await { folders("office-work") == workers }
            check(folders("office-ui") == sources)
            ui { intake!!.finish() }; intake = null; control("reset")
            result.putString("stream","CAPT_OFFICE_USER_WORKFLOW_PASSED (production SEND/picker callback, four formats, revoked grant/recreate, subset/Fit, held range/copies and cancellation; no USB)")
            code = Activity.RESULT_OK
        } catch (error: Throwable) {
            result.putString("stream","OFFICE_USER_WORKFLOW_FAILED:$stage:${error.javaClass.simpleName}")
        } finally {
            removeMonitor(monitor)
            held?.let { PrintQueueRuntime.cancel(it) }
            runOnMainSync { office?.finish(); intake?.finish() }
            uris.forEach { runCatching { control("revoke",it) } }
            runCatching { control("reset") }
        }
        finish(code,result)
    }
}
