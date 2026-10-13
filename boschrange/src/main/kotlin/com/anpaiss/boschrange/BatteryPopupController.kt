package com.anpaiss.boschrange

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView

/**
 * Overlay popup "Battery at start? / now? / at arrival?": one wheel 0..100 in
 * steps of 1 %, an optional DRY / WET / MUD row (the condition, recorded only)
 * and SET / SKIP. Same mechanism as the MTA popup of BetterETA: upper band of
 * the screen, NOT_FOCUSABLE so touches outside go to the ride app, auto-skip
 * after AUTO_DISMISS_MS.
 */
class BatteryPopupController(
    private val context: Context,
    private val logger: BoschRangeLogger,
) {
    class Request(
        val title: String,
        val initialPct: Int,
        val condition: Condition?,          // null = no condition row
        val onSet: (pct: Int, condition: Condition?) -> Unit,
        val onSkip: () -> Unit,
        val reminder: String? = null,          // extra row "<reminder> [FATTO]"; FATTO does not close the popup
        val onReminderDone: () -> Unit = {},
    )

    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var currentView: View? = null

    companion object {
        private const val AUTO_DISMISS_MS = 45_000L
    }

    fun canShow(): Boolean = Settings.canDrawOverlays(context)

    /** Shows the popup; any thread. A popup already on screen is replaced (its request is skipped). */
    fun show(req: Request) { handler.post { showOnMain(req) } }

    fun hide() { handler.post { removeCurrent() } }

    private fun showOnMain(req: Request) {
        if (!canShow()) { req.onSkip(); return }
        removeCurrent()
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val title = TextView(context).apply {
            text = req.title
            setTextColor(Color.WHITE)
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 1
            setShadowLayer(dp(2f), 0f, dp(1f), 0xB3000000.toInt())
        }
        val pickerCtx = ContextThemeWrapper(context, android.R.style.Theme_Material)
        val picker = NumberPicker(pickerCtx).apply {
            minValue = 0
            maxValue = 100
            value = req.initialPct.coerceIn(0, 100)
            wrapSelectorWheel = false
            setFormatter { "$it %" }
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            if (Build.VERSION.SDK_INT >= 29) setTextColor(Color.WHITE)
        }
        val pickerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(picker, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        var chosen = req.condition
        val conditionButtons = mutableMapOf<Condition, Button>()
        fun paintConditions() {
            for ((c, b) in conditionButtons) {
                b.background = GradientDrawable().apply {
                    cornerRadius = dp(6f)
                    setColor(Color.parseColor(if (c == chosen) "#1565C0" else "#424242"))
                }
            }
        }
        val conditionRow = if (req.condition == null) null else LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(dp(4f).toInt(), dp(6f).toInt(), dp(4f).toInt(), 0)
            for (c in Condition.entries) {
                val b = Button(context).apply {
                    text = when (c) {
                        Condition.DRY -> context.getString(R.string.condition_dry)
                        Condition.WET -> context.getString(R.string.condition_wet)
                        Condition.MUD -> context.getString(R.string.condition_mud)
                    }
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    setOnClickListener { chosen = c; paintConditions() }
                }
                conditionButtons[c] = b
                addView(b, LinearLayout.LayoutParams(lp))
            }
            paintConditions()
        }

        val reminderRow = if (req.reminder == null) null else LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val label = TextView(context).apply {
                text = req.reminder
                setTextColor(Color.parseColor("#FFB74D"))
                textSize = 14f
                maxLines = 3
            }
            val btnDone = Button(context).apply {
                text = context.getString(R.string.popup_done)
                setTextColor(Color.WHITE)
                textSize = 14f
                background = GradientDrawable().apply { cornerRadius = dp(6f); setColor(Color.parseColor("#1565C0")) }
                setOnClickListener {
                    isEnabled = false
                    text = "\u2713"
                    label.setTextColor(Color.parseColor("#81C784"))
                    logger.log("POPUP", "${req.title}: ${req.reminder} → FATTO")
                    req.onReminderDone()
                }
            }
            val lpLabel = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lpLabel.setMargins(dp(4f).toInt(), dp(6f).toInt(), dp(4f).toInt(), 0)
            val lpBtn = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lpBtn.setMargins(dp(4f).toInt(), dp(6f).toInt(), dp(4f).toInt(), 0)
            addView(label, lpLabel)
            addView(btnDone, lpBtn)
        }

        val btnSet = Button(context).apply {
            text = context.getString(R.string.popup_set)
            setTextColor(Color.WHITE)
            textSize = 16f
            background = GradientDrawable().apply { cornerRadius = dp(6f); setColor(Color.parseColor("#2E7D32")) }
            setOnClickListener {
                val pct = picker.value
                removeCurrent()
                logger.log("POPUP", "${req.title} → $pct %" + (chosen?.let { ", $it" } ?: ""))
                req.onSet(pct, chosen)
            }
        }
        val btnSkip = Button(context).apply {
            text = context.getString(R.string.popup_skip)
            setTextColor(Color.WHITE)
            textSize = 16f
            background = GradientDrawable().apply { cornerRadius = dp(6f); setColor(Color.parseColor("#C62828")) }
            setOnClickListener {
                removeCurrent()
                logger.log("POPUP", "${req.title} → skip")
                req.onSkip()
            }
        }
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(dp(4f).toInt(), dp(8f).toInt(), dp(4f).toInt(), 0)
            addView(btnSet, lp)
            addView(btnSkip, LinearLayout.LayoutParams(lp))
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(12f).toInt()
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = dp(10f)
                setColor(Color.parseColor("#EE1A1A1A"))
                setStroke(dp(2f).toInt(), Color.parseColor("#FF5823"))
            }
            addView(title)
            addView(pickerRow)
            conditionRow?.let { addView(it) }
            reminderRow?.let { addView(it) }
            addView(buttons)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = dp(40f).toInt()
            horizontalMargin = 0.04f
        }

        runCatching { wm.addView(root, params) }
            .onSuccess {
                currentView = root
                logger.log("POPUP", "Mostrato: ${req.title} (default ${req.initialPct} %)")
                handler.postDelayed({
                    if (currentView === root) {
                        removeCurrent()
                        logger.log("POPUP", "${req.title} → chiuso per timeout")
                        req.onSkip()
                    }
                }, AUTO_DISMISS_MS)
            }
            .onFailure { e ->
                logger.logError("POPUP", "addView fallita", e)
                req.onSkip()
            }
    }

    private fun removeCurrent() {
        currentView?.let { v -> runCatching { windowManager?.removeView(v) } }
        currentView = null
    }

    private fun dp(v: Float): Float = v * context.resources.displayMetrics.density
}
