package com.cryonum.captusbprint

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import kotlin.math.roundToInt

/** Display-only card. The preview never submits print or USB commands. */
internal class DocumentPreviewPanel(context: Context) : LinearLayout(context) {
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
        Configuration.UI_MODE_NIGHT_YES
    private val foreground = if (dark) Color.WHITE else Color.rgb(29, 36, 49)
    private val muted = if (dark) Color.rgb(187, 196, 210) else Color.rgb(85, 96, 112)
    private val image = ImageView(context).apply {
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_CENTER
        setBackgroundColor(Color.WHITE)
        contentDescription = "Предварительный просмотр страницы"
    }
    private val spinner = ProgressBar(context)
    private val state = TextView(context).apply {
        textSize = 14f
        setTextColor(muted)
        gravity = Gravity.CENTER
    }
    private val previous = Button(context).apply {
        text = "‹"
        contentDescription = "Предыдущая страница"
    }
    private val next = Button(context).apply {
        text = "›"
        contentDescription = "Следующая страница"
    }
    private val page = TextView(context).apply {
        textSize = 15f
        setTextColor(this@DocumentPreviewPanel.foreground)
        gravity = Gravity.CENTER
    }
    private val navigation = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    var onPrevious: () -> Unit = {}
    var onNext: () -> Unit = {}

    init {
        orientation = VERTICAL
        visibility = View.GONE
        setPadding(dp(14), dp(14), dp(14), dp(12))
        background = GradientDrawable().apply {
            setColor(if (dark) Color.rgb(39, 43, 52) else Color.rgb(246, 247, 250))
            cornerRadius = dp(18).toFloat()
        }
        addView(TextView(context).apply {
            text = "ПРЕДПРОСМОТР ДОКУМЕНТА"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(this@DocumentPreviewPanel.foreground)
            letterSpacing = 0.08f
        })
        addView(TextView(context).apply {
            text = "Просмотр без USB-печати · файл остаётся на устройстве"
            textSize = 12f
            setTextColor(muted)
            setPadding(0, dp(4), 0, dp(12))
        })
        val canvas = FrameLayout(context).apply {
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                setColor(if (dark) Color.rgb(25, 28, 34) else Color.rgb(227, 230, 236))
                cornerRadius = dp(12).toFloat()
            }
        }
        image.elevation = dp(3).toFloat()
        canvas.addView(image, FrameLayout.LayoutParams(-1, dp(420), Gravity.CENTER))
        canvas.addView(spinner, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER))
        canvas.addView(state, FrameLayout.LayoutParams(-1, -2,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(12) })
        addView(canvas, LayoutParams(-1, dp(440)))
        previous.setOnClickListener { onPrevious() }
        next.setOnClickListener { onNext() }
        navigation.addView(previous, LayoutParams(0, -2, 1f))
        navigation.addView(page, LayoutParams(0, -2, 2f))
        navigation.addView(next, LayoutParams(0, -2, 1f))
        addView(navigation)
        setLoading()
    }

    fun showDocument() { visibility = View.VISIBLE }

    fun setLoading() {
        spinner.visibility = View.VISIBLE
        state.text = "Готовим изображение страницы…"
        state.visibility = View.VISIBLE
        image.visibility = View.INVISIBLE
        previous.isEnabled = false
        next.isEnabled = false
    }

    fun showBitmap(bitmap: Bitmap, position: Int, total: Int) {
        val maxWidth = minOf((resources.displayMetrics.widthPixels - dp(82))
            .coerceAtLeast(dp(180)), dp(500))
        val maxHeight = dp(420)
        val factor = minOf(maxWidth.toFloat() / bitmap.width,
            maxHeight.toFloat() / bitmap.height)
        image.layoutParams = FrameLayout.LayoutParams(
            (bitmap.width * factor).roundToInt().coerceAtLeast(1),
            (bitmap.height * factor).roundToInt().coerceAtLeast(1), Gravity.CENTER)
        image.setImageBitmap(bitmap)
        image.visibility = View.VISIBLE
        spinner.visibility = View.GONE
        state.visibility = View.GONE
        navigation.visibility = if (total > 1) View.VISIBLE else View.GONE
        page.text = "Страница ${position + 1} из $total"
        previous.isEnabled = position > 0
        next.isEnabled = position < total - 1
    }

    fun showError(message: String) {
        image.setImageDrawable(null)
        image.visibility = View.INVISIBLE
        spinner.visibility = View.GONE
        state.text = message
        state.visibility = View.VISIBLE
        previous.isEnabled = false
        next.isEnabled = false
    }

    fun clearImage() {
        image.setImageDrawable(null)
    }
}
