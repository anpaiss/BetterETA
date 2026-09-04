package com.anpaiss.bettereta

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
 * Popup overlay "Mandatory arrival time?" alla partenza: due rotelle (ora,
 * minuti a passi di 15) con SET / DISMISS.
 *
 * Stessa tecnica del PopupController di PoiAhead (overlay già provato sul
 * k24): fascia alta perché la bassa è coperta dal banner "next cue";
 * NOT_FOCUSABLE così i tocchi fuori dal popup passano al ride-app.
 * Auto-dismiss dopo AUTO_DISMISS_MS = nessun MTA.
 */
class MtaPopupController(
    private val context: Context,
    private val logger: BetterEtaLogger,
    private val onSet: (hour: Int, minute: Int) -> Unit,
    private val onDismiss: () -> Unit,
) {

    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var currentView: View? = null

    companion object {
        // Le rotelle richiedono più tempo dei due bottoni di PoiAhead (25 s)
        private const val AUTO_DISMISS_MS = 45_000L
        private val MINUTE_LABELS = Array(60 / ArrivalMargin.MINUTE_STEP) { "%02d".format(it * ArrivalMargin.MINUTE_STEP) }
    }

    fun canShow(): Boolean = Settings.canDrawOverlays(context)

    /** Mostra il popup con le rotelle preimpostate su [hour]:[minute]. Chiamabile da qualsiasi thread. */
    fun show(hour: Int, minute: Int) {
        handler.post { showOnMain(hour, minute) }
    }

    fun hide() {
        handler.post { removeCurrent() }
    }

    private fun showOnMain(hour: Int, minute: Int) {
        if (!canShow()) return
        removeCurrent()

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val title = TextView(context).apply {
            text = context.getString(R.string.mta_popup_title)
            setTextColor(Color.WHITE)
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 1
            setShadowLayer(dp(2f), 0f, dp(1f), 0xB3000000.toInt())
        }

        // Tema Material scuro: testo bianco delle rotelle sul fondo del popup
        // (il context del service non ha tema e il testo verrebbe nero, illeggibile)
        val pickerCtx = ContextThemeWrapper(context, android.R.style.Theme_Material)
        val hourPicker = NumberPicker(pickerCtx).apply {
            minValue = 0
            maxValue = 23
            setFormatter { "%02d".format(it) }
            value = hour
            wrapSelectorWheel = true
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS   // niente tastiera
            if (Build.VERSION.SDK_INT >= 29) setTextColor(Color.WHITE)
        }
        val minutePicker = NumberPicker(pickerCtx).apply {
            minValue = 0
            maxValue = MINUTE_LABELS.size - 1
            displayedValues = MINUTE_LABELS
            value = (minute / ArrivalMargin.MINUTE_STEP).coerceIn(0, MINUTE_LABELS.size - 1)
            wrapSelectorWheel = true
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            if (Build.VERSION.SDK_INT >= 29) setTextColor(Color.WHITE)
        }
        val colon = TextView(context).apply {
            text = ":"
            setTextColor(Color.WHITE)
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        val pickers = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(dp(4f).toInt(), dp(4f).toInt(), dp(4f).toInt(), 0)
            addView(hourPicker, lp)
            addView(colon, LinearLayout.LayoutParams(lp))
            addView(minutePicker, LinearLayout.LayoutParams(lp))
        }

        val btnSet = Button(context).apply {
            text = context.getString(R.string.mta_popup_set)
            setTextColor(Color.WHITE)
            textSize = 16f
            background = GradientDrawable().apply {
                cornerRadius = dp(6f)
                setColor(Color.parseColor("#1565C0"))   // bianco su blu 5.8:1
            }
            setOnClickListener {
                val h = hourPicker.value
                val m = minutePicker.value * ArrivalMargin.MINUTE_STEP
                removeCurrent()
                onSet(h, m)
            }
        }
        val btnDismiss = Button(context).apply {
            text = context.getString(R.string.mta_popup_dismiss)
            setTextColor(Color.WHITE)
            textSize = 16f
            background = GradientDrawable().apply {
                cornerRadius = dp(6f)
                setColor(Color.parseColor("#C62828"))   // bianco su rosso 5.6:1
            }
            setOnClickListener {
                removeCurrent()
                onDismiss()
            }
        }
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(dp(4f).toInt(), dp(8f).toInt(), dp(4f).toInt(), 0)
            addView(btnSet, lp)
            addView(btnDismiss, LinearLayout.LayoutParams(lp))
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(12f).toInt()
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = dp(10f)
                setColor(Color.parseColor("#EE1A1A1A"))
                setStroke(dp(2f).toInt(), Color.parseColor("#2196F3"))
            }
            addView(title)
            addView(pickers)
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
                logger.log("MTA  ", "Popup mostrato: default %02d:%02d".format(hour, minute))
                handler.postDelayed({ autoDismiss(root) }, AUTO_DISMISS_MS)
            }
            .onFailure { e ->
                logger.logError("MTA  ", "addView popup fallita", e)
                onDismiss()
            }
    }

    private fun autoDismiss(view: View) {
        if (currentView === view) {
            removeCurrent()
            logger.log("MTA  ", "Popup chiuso per timeout")
            onDismiss()
        }
    }

    private fun removeCurrent() {
        currentView?.let { v -> runCatching { windowManager?.removeView(v) } }
        currentView = null
    }

    private fun dp(v: Float): Float = v * context.resources.displayMetrics.density
}
