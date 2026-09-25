package com.anpaiss.bettereta

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import kotlin.math.max
import kotlin.math.min

/**
 * Numeri grandi della dashboard, disegnati come in BigFields (25/09/2026):
 * immagine a un canale colorata dalla ImageView, cifre adattate al loro
 * contorno vero e non alla riga di testo, carattere Barlow Condensed (OFL,
 * res/font). L'autosize delle TextView lasciava le cifre alte circa metà
 * della cella.
 */
object BigNumber {

    /** Quota della larghezza e dell'altezza disponibili che le cifre possono occupare. */
    private const val FILL_W = 0.96f
    private const val FILL_H = 0.86f
    private const val REF_SIZE = 200f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val bounds = Rect()
    @Volatile private var refDigitH = 0f

    fun init(context: Context) {
        if (refDigitH > 0f) return
        synchronized(paint) {
            paint.typeface = ResourcesCompat.getFont(context.applicationContext, R.font.barlow_condensed_bold)
                ?: Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            paint.textSize = REF_SIZE
            paint.getTextBounds("8", 0, 1, bounds)
            refDigitH = bounds.height().toFloat()
        }
    }

    /** Altezza massima delle cifre perché [template] stia in [w]×[h]. */
    fun maxDigitHeight(template: String, w: Int, h: Int): Float = synchronized(paint) {
        if (refDigitH <= 0f) return 0f
        paint.textSize = REF_SIZE
        paint.getTextBounds(template, 0, template.length, bounds)
        val tw = paint.measureText(template)
        val th = bounds.height().toFloat()
        if (tw <= 0f || th <= 0f) return 0f
        refDigitH * min(w * FILL_W / tw, h * FILL_H / th)
    }

    /**
     * [text] con cifre alte [digitH] px, centrato, in un bitmap [w]×[h]. Un testo
     * più largo del previsto (uno stato come "Off route") si rimpicciolisce da
     * solo invece di uscire dalla cella.
     */
    fun render(text: String, w: Int, h: Int, digitH: Float): Bitmap {
        val bmp = Bitmap.createBitmap(max(w, 1), max(h, 1), Bitmap.Config.ALPHA_8)
        if (digitH <= 0f || refDigitH <= 0f) return bmp
        synchronized(paint) {
            paint.textSize = REF_SIZE * digitH / refDigitH
            val maxW = w * FILL_W
            paint.measureText(text).let { if (it > maxW) paint.textSize *= maxW / it }
            val drawnH = refDigitH * paint.textSize / REF_SIZE
            val tw = paint.measureText(text)
            Canvas(bmp).drawText(text, ((w - tw) / 2f).coerceAtLeast(0f), (h + drawnH) / 2f, paint)
        }
        return bmp
    }
}
