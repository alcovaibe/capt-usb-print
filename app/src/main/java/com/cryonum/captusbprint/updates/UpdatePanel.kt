package com.cryonum.captusbprint.updates

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.cryonum.captusbprint.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Native counterpart of Bluetooth Disable's 56dp control and 32dp rounded bottom sheet. */
internal class UpdatePanel(private val activity: Activity, private val session: UpdateSession) {
    private val controller get() = session.controller
    private fun dp(n: Int) = (n * activity.resources.displayMetrics.density).toInt()
    private fun color(attr: Int): Int {
        val attrs = activity.obtainStyledAttributes(intArrayOf(attr))
        return try { attrs.getColor(0, Color.BLACK) } finally { attrs.recycle() }
    }
    private val inkColor get() = color(android.R.attr.textColorPrimary)
    private val accent get() = color(android.R.attr.colorAccent)
    private var observation: Job? = null
    private var retryJob: Job? = null
    private var installJob: Job? = null
    private var dialog: Dialog? = null
    private var shownStatus: UpdateStatus? = null
    private var progressText: TextView? = null
    private var progressBar: ProgressBar? = null
    private var installing = false
    private var active = false
    val view = FrameLayout(activity)
    private val button = ImageButton(activity).apply {
        setImageResource(R.drawable.ic_check_updates)
        imageTintList = ColorStateList.valueOf(inkColor)
        contentDescription = activity.getString(R.string.updates_check)
        tooltipText = contentDescription
        val ripple = TypedValue()
        activity.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
        setBackgroundResource(ripple.resourceId)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        setOnClickListener { controller.check() }
    }
    private val spinner = ProgressBar(activity).apply {
        isIndeterminate = true
        indeterminateTintList = ColorStateList.valueOf(accent)
        visibility = android.view.View.GONE
        importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    init {
        view.addView(button, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER))
        view.addView(spinner, FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER))
    }
    fun resume() {
        active = true
        installing = false
        controller.enterMain()
        observation?.cancel()
        observation = session.scope.launch { controller.state.collectLatest { render(it) } }
    }
    fun pause() {
        active = false
        observation?.cancel()
        observation = null
        retryJob?.cancel()
        installJob?.cancel()
        hide()
        controller.leaveMain()
    }
    private fun hide() {
        retryJob?.cancel()
        dialog?.setOnCancelListener(null)
        dialog?.dismiss()
        dialog = null
        shownStatus = null
        progressText = null
        progressBar = null
    }
    private fun label(value: String, size: Float = 16f) = TextView(activity).apply {
        text = value; textSize = size; setTextColor(inkColor)
    }
    private fun render(state: UpdateUiState) {
        button.isEnabled = state.status !in setOf(UpdateStatus.CHECKING, UpdateStatus.DOWNLOADING)
        button.imageAlpha = if (button.isEnabled) 255 else 100
        spinner.visibility = if (state.status == UpdateStatus.CHECKING) android.view.View.VISIBLE else android.view.View.GONE
        if (state.status == UpdateStatus.DOWNLOADING && shownStatus == state.status) {
            updateProgress(state); return
        }
        hide()
        if (state.status in setOf(UpdateStatus.IDLE, UpdateStatus.CHECKING) || !active) return
        shownStatus = state.status
        val sheet = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            background = GradientDrawable().apply {
                setColor(color(android.R.attr.colorBackground))
                cornerRadii = floatArrayOf(dp(32).toFloat(), dp(32).toFloat(), dp(32).toFloat(), dp(32).toFloat(), 0f, 0f, 0f, 0f)
            }
        }
        val content = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val release = state.release
        val title = if (release != null) activity.getString(R.string.updates_available, release.versionName) else activity.getString(R.string.updates_check)
        content.addView(label(title, 20f).apply { setTypeface(typeface, Typeface.BOLD) })
        fun body(text: String) { content.addView(label(text).apply { setPadding(0, dp(12), 0, 0) }) }
        if (release != null) body(if (activity.resources.configuration.locales[0].language == "ru") release.ru else release.en)
        val message = when (state.status) {
            UpdateStatus.CURRENT -> R.string.updates_current
            UpdateStatus.NETWORK_ERROR -> R.string.updates_network_error
            UpdateStatus.INVALID_RESPONSE -> R.string.updates_invalid_response
            UpdateStatus.INTERNAL_DISABLED -> R.string.updates_disabled
            UpdateStatus.READY -> R.string.updates_ready
            UpdateStatus.DOWNLOAD_ERROR -> R.string.updates_download_error
            UpdateStatus.INVALID_APK -> R.string.updates_invalid_apk
            UpdateStatus.INSTALL_ERROR -> R.string.updates_install_error
            else -> null
        }
        if (message != null) body(activity.getString(message))
        if (state.status == UpdateStatus.DOWNLOADING) {
            progressText = label("").apply { setPadding(0, dp(12), 0, 0) }
            content.addView(progressText)
            progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
            content.addView(progressBar, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(12) })
            updateProgress(state)
        }
        val scroll = ScrollView(activity).apply { addView(content) }
        // Bound content for small screens and large fonts; actions stay outside the scroll.
        val maxHeight = (activity.resources.displayMetrics.heightPixels * .55f).toInt()
        content.measure(android.view.View.MeasureSpec.makeMeasureSpec(activity.resources.displayMetrics.widthPixels - dp(48), android.view.View.MeasureSpec.AT_MOST), android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED))
        sheet.addView(scroll, LinearLayout.LayoutParams(-1, minOf(content.measuredHeight, maxHeight)))
        val actions = LinearLayout(activity).apply { gravity = Gravity.END; setPadding(0, dp(12), 0, 0) }
        fun action(id: Int, click: () -> Unit): TextView = label(activity.getString(id)).apply {
            gravity = Gravity.CENTER; minHeight = dp(48)
            setPadding(dp(12), 0, dp(12), 0); setTextColor(accent)
            setTypeface(typeface, Typeface.BOLD); isClickable = true; isFocusable = true
            val ripple = TypedValue(); activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
            setBackgroundResource(ripple.resourceId)
            setOnClickListener { click() }
            actions.addView(this)
        }
        if (release != null) {
            action(if (state.status == UpdateStatus.DOWNLOADING) R.string.updates_cancel else R.string.updates_later) { controller.defer() }
            when (state.status) {
                UpdateStatus.READY, UpdateStatus.INSTALL_ERROR -> action(R.string.updates_install) { install() }
                UpdateStatus.DOWNLOADING -> Unit
                else -> action(if (state.status == UpdateStatus.AVAILABLE) R.string.updates_download else R.string.updates_retry) { controller.download() }
            }
        } else {
            if (state.status in setOf(UpdateStatus.NETWORK_ERROR, UpdateStatus.INVALID_RESPONSE)) {
                val retry = action(R.string.updates_retry) { controller.dismiss(); controller.check() }
                retry.isEnabled = false; retry.alpha = .4f
                retryJob = session.scope.launch { delay(5000); if (dialog != null && shownStatus == state.status) { retry.isEnabled = true; retry.alpha = 1f } }
            }
            action(R.string.updates_close) { controller.dismiss() }
        }
        sheet.addView(actions, LinearLayout.LayoutParams(-1, -2))
        dialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setContentView(sheet)
            setCanceledOnTouchOutside(true)
            setOnCancelListener { controller.defer() }
            window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window?.setGravity(Gravity.BOTTOM)
            show()
            window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }
    private fun updateProgress(state: UpdateUiState) {
        val total = state.total
        val percent = if (total != null && total > 0) ((state.received * 100) / total).toInt().coerceIn(0, 100) else null
        progressText?.text = if (percent == null) activity.getString(R.string.updates_downloading) else activity.getString(R.string.updates_downloading_percent, percent)
        progressBar?.isIndeterminate = percent == null
        if (percent != null) progressBar?.progress = percent
    }
    private fun install() {
        if (installing || !active) return
        installing = true
        installJob = session.scope.launch {
            try {
                val file = controller.verifiedApk() ?: return@launch
                if (!active) return@launch
                if (!activity.packageManager.canRequestPackageInstalls()) {
                    activity.startActivity(UpdateInstaller.permissionIntent(activity))
                } else {
                    val intent = UpdateInstaller.installIntent(activity, file)
                    intent.clipData = ClipData.newRawUri("CAPT update", intent.data)
                    activity.startActivity(intent)
                    controller.installerOpened(file)
                }
            } catch (error: kotlinx.coroutines.CancellationException) { throw error
            } catch (_: Exception) { controller.installFailed() }
            finally { installing = false }
        }
    }
}
