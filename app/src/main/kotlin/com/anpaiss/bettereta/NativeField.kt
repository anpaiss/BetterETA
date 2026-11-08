package com.anpaiss.bettereta

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.util.DisplayMetrics
import android.util.TypedValue
import android.widget.RemoteViews
import io.hammerhead.karooext.models.ViewConfig
import kotlin.math.max

/**
 * Campi singoli nello stile KAROO, disegnati come i campi nativi del Karoo 3
 * (misurati l'8/11 a 300 dpi, celle da 124 px): niente fondo né bordo, titolo
 * maiuscolo e valore nel font di sistema IBM Plex Sans Condensed, nero sul tema
 * chiaro e bianco su quello scuro, allineati come chiede l'impostazione del
 * Karoo. Il titolo è nostro e non l'intestazione del Karoo, così tiene il "±",
 * il nome del POI e così via.
 *
 * Geometria nativa: le cifre hanno la misura di config.textSize e poggiano poco
 * sopra il fondo della cella (4 px a 41sp, 9 px a 54sp); il titolo, alto 23 px
 * di maiuscole e su due righe a passo 26 px, sta centrato nello spazio sopra le
 * cifre. Un messaggio senza cifre ("No Route") è piccolo e sta centrato sotto
 * il titolo. Tutto in un bitmap, come BigNumber: con le TextView le cifre
 * uscivano dal fondo della cella.
 */
object NativeField {

    private const val PAD_H_DP = 4f
    private const val TITLE_CAP_DP = 23f / 1.875f
    private const val TITLE_PITCH_DP = 26f / 1.875f
    private const val STATUS_SP = 19f        // "No Route" nativo: maiuscole alte 24 px, largo 131 px

    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = Typeface.create("ibm-plex-sans-condensed", Typeface.NORMAL)
    }
    private val bounds = Rect()

    fun views(context: Context, config: ViewConfig, title: String, value: String): RemoteViews {
        val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        return RemoteViews(context.packageName, R.layout.view_native).apply {
            setImageViewBitmap(R.id.iv_native, render(context.resources.displayMetrics, config, title, value))
            setInt(R.id.iv_native, "setColorFilter", if (night) 0xFFFFFFFF.toInt() else 0xFF000000.toInt())
        }
    }

    private fun render(dm: DisplayMetrics, config: ViewConfig, title: String, value: String): Bitmap = synchronized(paint) {
        val w = max(config.viewSize.first, 1)
        val h = max(config.viewSize.second, 1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val canvas = Canvas(bmp)
        val pad = PAD_H_DP * dm.density
        val room = w - 2 * pad
        fun x(textW: Float) = when (config.alignment) {
            ViewConfig.Alignment.LEFT  -> pad
            ViewConfig.Alignment.RIGHT -> w - pad - textW
            else                       -> (w - textW) / 2f
        }

        // Cifre: misura del Karoo, ridotta solo se il valore non sta in larghezza.
        val digitPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, config.textSize.toFloat(), dm)
        paint.textSize = digitPx
        paint.getTextBounds("8", 0, 1, bounds)
        val digitBase = h - max(1f, 0.2f * digitPx - 5.0f * dm.density)
        val digitTop = digitBase - bounds.height()

        // Titolo centrato sopra le cifre, al massimo due righe, "…" se non basta.
        paint.textSize = 100f
        paint.getTextBounds("H", 0, 1, bounds)
        paint.textSize = 100f * TITLE_CAP_DP * dm.density / bounds.height()
        val capH = TITLE_CAP_DP * dm.density
        val pitch = TITLE_PITCH_DP * dm.density
        val lines = titleLines(title.uppercase(), room)
        val blockH = capH + (lines.size - 1) * pitch
        var base = (digitTop - blockH) / 2f + capH
        for (line in lines) {
            canvas.drawText(line, x(paint.measureText(line)), base, paint)
            base += pitch
        }
        val titleBottom = base - pitch

        val status = value.none { it.isDigit() }
        paint.textSize = if (status) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, STATUS_SP, dm) else digitPx
        paint.measureText(value).let { if (it > room) paint.textSize *= room / it }
        val textW = paint.measureText(value)
        if (status) {
            paint.getTextBounds("H", 0, 1, bounds)
            canvas.drawText(value, x(textW), (titleBottom + h + bounds.height()) / 2f, paint)
        } else {
            canvas.drawText(value, x(textW), digitBase, paint)
        }
        bmp
    }

    /** Le righe del titolo: quelle date con "\n", oppure una riga lunga spezzata tra le parole. */
    private fun titleLines(title: String, room: Float): List<String> {
        var lines = title.split('\n')
        if (lines.size == 1 && paint.measureText(title) > room && ' ' in title) {
            val words = title.split(' ')
            var cut = words.size - 1
            while (cut > 1 && paint.measureText(words.take(cut).joinToString(" ")) > room) cut--
            lines = listOf(words.take(cut).joinToString(" "), words.drop(cut).joinToString(" "))
        }
        return lines.take(2).map { TextUtils.ellipsize(it, paint, room, TextUtils.TruncateAt.END).toString() }
    }
}
