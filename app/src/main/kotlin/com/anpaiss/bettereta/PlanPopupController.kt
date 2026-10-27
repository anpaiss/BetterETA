package com.anpaiss.bettereta

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Popup overlay della stima a tavolino: route aperta, giro non partito.
 * Quasi a tutto schermo, numeri grandi, DISMISS in fondo (Andrea, 27/10:
 * l'InRideAlert del Karoo era troppo piccolo per leggere i dati).
 *
 * Stessa tecnica di [MtaPopupController]: overlay NOT_FOCUSABLE, i tocchi
 * fuori dal popup passano al ride-app. Si chiude da solo dopo AUTO_DISMISS_MS,
 * alla partenza del giro e al cambio di route.
 */
class PlanPopupController(
    private val context: Context,
    private val logger: BetterEtaLogger,
) {

    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var currentView: View? = null

    companion object {
        private const val AUTO_DISMISS_MS = 90_000L
    }

    fun canShow(): Boolean = Settings.canDrawOverlays(context)

    /** [time] "1:03", [routeName] della route, [lines] righe di dettaglio. Chiamabile da qualsiasi thread. */
    fun show(time: String, routeName: String, lines: List<String>) {
        handler.post { showOnMain(time, routeName, lines) }
    }

    fun hide() {
        handler.post { removeCurrent() }
    }

    private fun showOnMain(time: String, routeName: String, lines: List<String>) {
        if (!canShow()) return
        removeCurrent()

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        fun label(text: String, sizeSp: Float, bold: Boolean, color: Int = Color.WHITE, lines: Int = 1) = TextView(context).apply {
            this.text = text
            setTextColor(color)
            textSize = sizeSp
            if (bold) setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = lines
            ellipsize = TextUtils.TruncateAt.END
        }

        val title = label(context.getString(R.string.plan_popup_title), 22f, bold = true)
        val name  = label(routeName, 16f, bold = false, color = Color.parseColor("#BBBBBB"), lines = 2)
        val big   = label(time, 72f, bold = true)
        val btnDismiss = Button(context).apply {
            text = context.getString(R.string.plan_popup_dismiss)
            setTextColor(Color.WHITE)
            textSize = 20f
            background = GradientDrawable().apply {
                cornerRadius = dp(8f)
                setColor(Color.parseColor("#1565C0"))   // bianco su blu 5.8:1
            }
            setOnClickListener {
                removeCurrent()
                logger.log("PLAN ", "Popup chiuso con DISMISS")
            }
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val pad = dp(12f).toInt()
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = dp(12f)
                setColor(Color.parseColor("#F21A1A1A"))
                setStroke(dp(2f).toInt(), Color.parseColor("#2196F3"))
            }
            val wrap = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(title, wrap)
            addView(name, LinearLayout.LayoutParams(wrap).apply { topMargin = dp(4f).toInt() })
            addView(big, LinearLayout.LayoutParams(wrap).apply { topMargin = dp(8f).toInt() })
            // Una riga per dato: "33.5 km · 1235 m D+" non entrava nei 480 px del Karoo
            for (l in lines) addView(label(l, 26f, bold = false), LinearLayout.LayoutParams(wrap).apply { topMargin = dp(4f).toInt() })
            addView(btnDismiss, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56f).toInt()).apply {
                topMargin = dp(14f).toInt()
            })
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
            y = dp(12f).toInt()
            horizontalMargin = 0.03f
        }

        runCatching { wm.addView(root, params) }
            .onSuccess {
                currentView = root
                handler.postDelayed({ autoDismiss(root) }, AUTO_DISMISS_MS)
            }
            .onFailure { e -> logger.logError("PLAN ", "addView popup fallita", e) }
    }

    private fun autoDismiss(view: View) {
        if (currentView === view) {
            removeCurrent()
            logger.log("PLAN ", "Popup chiuso per timeout")
        }
    }

    private fun removeCurrent() {
        currentView?.let { v -> runCatching { windowManager?.removeView(v) } }
        currentView = null
    }

    private fun dp(v: Float): Float = v * context.resources.displayMetrics.density
}
