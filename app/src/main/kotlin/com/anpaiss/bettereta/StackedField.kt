package com.anpaiss.bettereta

import android.content.Context
import android.widget.RemoteViews
import io.hammerhead.karooext.models.ViewConfig

/**
 * The coloured styles in a narrow cell: caption on top, value below across the
 * whole width (view_stacked). A Karoo data page holds at most ten fields, five
 * rows of two, each 238 px wide: there the side-by-side layouts cut both the
 * captions and the values.
 */
object StackedField {

    /** Half the page is 238-240 px, a full row 478. */
    private const val NARROW_BELOW_PX = 300

    fun narrow(config: ViewConfig): Boolean = config.viewSize.first < NARROW_BELOW_PX

    fun views(context: Context, c: FieldColors, label: String, value: String, valueColor: Int = c.value): RemoteViews =
        RemoteViews(context.packageName, R.layout.view_stacked).apply {
            if (c.bgRes != null) setInt(R.id.root_stacked, "setBackgroundResource", c.bgRes)
            else setInt(R.id.root_stacked, "setBackgroundColor", c.bgColor ?: 0)
            setTextColor(R.id.tv_stacked_label, c.label)
            setTextColor(R.id.tv_stacked_value, valueColor)
            setTextViewText(R.id.tv_stacked_label, label)
            setTextViewText(R.id.tv_stacked_value, value)
        }
}
