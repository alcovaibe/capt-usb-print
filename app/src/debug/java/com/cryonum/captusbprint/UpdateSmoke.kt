package com.cryonum.captusbprint

import android.app.Activity
import android.app.Instrumentation
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageButton
import androidx.core.content.FileProvider
import com.cryonum.captusbprint.updates.UpdateInstaller
import java.io.File

/** Real native control, debug isolation and provider checks, without network or installer launch. */
class UpdateSmoke : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result = Bundle()
        var screen: Activity? = null
        val apk = File(targetContext.cacheDir, "updates/smoke.apk")
        try {
            check(!BuildConfig.PUBLIC_UPDATE_CHECKS)
            screen = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitForIdleSync()
            val activity = checkNotNull(screen)
            val description = activity.getString(R.string.updates_check)
            fun find(view: View): ImageButton? {
                if (view is ImageButton && view.contentDescription == description) return view
                if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                return null
            }
            lateinit var control: ImageButton
            runOnMainSync {
                control = checkNotNull(find(activity.window.decorView)) { "Missing update control" }
                val expected = (56 * activity.resources.displayMetrics.density).toInt()
                check(control.width == expected && control.height == expected) { "Wrong update touch target" }
                val position = IntArray(2); control.getLocationOnScreen(position)
                check(position[0] > activity.resources.displayMetrics.widthPixels / 2)
                check(position[1] > activity.resources.displayMetrics.heightPixels / 2)
                check(control.isEnabled)
            }
            screenshot("updates-main.png")
            uiAutomation.serviceInfo?.let { service ->
                service.flags = service.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                uiAutomation.serviceInfo = service
            }
            runOnMainSync { control.performClick() }
            waitForIdleSync()
            val deadline = SystemClock.elapsedRealtime() + 10000
            val expectedMessage = activity.getString(R.string.updates_disabled)
            fun contains(node: AccessibilityNodeInfo?): Boolean {
                if (node == null) return false
                if (node.isVisibleToUser && node.text?.toString() == expectedMessage) return true
                for (i in 0 until node.childCount) if (contains(node.getChild(i))) return true
                return false
            }
            // Android 16 may keep the Activity as the active accessibility window
            // while the native bottom Dialog is already visible in another window.
            fun visibleMessage(): Boolean = uiAutomation.windows.orEmpty().any { window ->
                val root = window.root
                root?.packageName?.toString() == targetContext.packageName && contains(root)
            } || contains(uiAutomation.rootInActiveWindow)
            while (!visibleMessage() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
            check(visibleMessage()) { "Manual debug check did not explain release-channel isolation" }
            screenshot("updates-result.png")
            apk.parentFile!!.mkdirs(); apk.writeBytes(byteArrayOf(1, 2, 3))
            val intent = UpdateInstaller.installIntent(targetContext, apk)
            check(intent.data?.scheme == "content")
            check(intent.type == "application/vnd.android.package-archive")
            check(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            targetContext.contentResolver.openInputStream(intent.data!!)!!.use { check(it.readBytes().contentEquals(apk.readBytes())) }
            try {
                FileProvider.getUriForFile(targetContext, "${targetContext.packageName}.updates", File(targetContext.filesDir, "private-report.json"))
                error("Update provider exposes private files")
            } catch (_: IllegalArgumentException) { }
            result.putString("stream", "CAPT_UPDATE_UI_SMOKE_PASSED\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            runCatching { screenshot("updates-result.png") }
            result.putString("stream", "CAPT_UPDATE_UI_SMOKE_FAILED: ${error.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            apk.delete()
            screen?.let { activity -> runOnMainSync { activity.finish() } }
        }
    }
    private fun screenshot(name: String) {
        val bitmap = checkNotNull(uiAutomation.takeScreenshot())
        try {
            val file = File(targetContext.getExternalFilesDir(null), name)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
}
