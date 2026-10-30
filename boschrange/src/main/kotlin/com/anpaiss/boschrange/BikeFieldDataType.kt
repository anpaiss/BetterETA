package com.anpaiss.boschrange

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import com.anpaiss.bettereta.throttle
import com.anpaiss.bettereta.whileVisible
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The last values received from the bike, merged notification after notification (each carries only what changed). */
data class BikeState(
    val connected: Boolean = false,
    val socPct: Int? = null,
    val riderPowerW: Int? = null,
    val speedKmh: Double? = null,
    val cadenceRpm: Int? = null,
    val odometerM: Long? = null,
    val lightOn: Boolean? = null,
    val lux: Double? = null,
    val chargerConnected: Boolean? = null,
    val locked: Boolean? = null,
) {
    fun merge(d: LiveData) = copy(
        socPct = d.socPct ?: socPct, riderPowerW = d.riderPowerW ?: riderPowerW, speedKmh = d.speedKmh ?: speedKmh,
        cadenceRpm = d.cadenceRpm ?: cadenceRpm, odometerM = d.odometerM ?: odometerM, lightOn = d.lightOn ?: lightOn,
        lux = d.lux ?: lux, chargerConnected = d.chargerConnected ?: chargerConnected, locked = d.locked ?: locked)
}

/**
 * Single-cell fields fed straight by the bike over the Live Data Interface
 * (30/10/2026): battery, rider power (the Bosch torque sensor), wheel speed,
 * cadence, odometer, light. "--" while the bike is off or not paired. Same
 * layout as the range fields, no tap. The stream carries the number, so the
 * value also goes where the Karoo accepts an extension data type.
 */
class BikeFieldDataType(
    extension: String,
    typeId: String,
    private val kind: Kind,
    private val state: StateFlow<BikeState>,
    private val visibleDataTypeIds: StateFlow<Set<String>?>,
) : DataTypeImpl(extension, typeId) {

    enum class Kind { BATTERY, POWER, SPEED, CADENCE, ODOMETER, LIGHT }
    private data class Face(val caption: String, val value: String, val sub: String)

    companion object {
        private const val REDRAW_THROTTLE_MS = 1_000L
        private const val CHAR_EM = 0.62f
    }

    private fun number(s: BikeState): Double? {
        if (!s.connected) return null
        return when (kind) {
            Kind.BATTERY  -> s.socPct?.toDouble()
            Kind.POWER    -> s.riderPowerW?.toDouble()
            Kind.SPEED    -> s.speedKmh?.let { Math.round(it * 10.0) / 10.0 }
            Kind.CADENCE  -> s.cadenceRpm?.toDouble()
            Kind.ODOMETER -> s.odometerM?.let { Math.round(it / 100.0) / 10.0 }
            Kind.LIGHT    -> s.lightOn?.let { if (it) 1.0 else 0.0 }
        }
    }

    override fun startStream(emitter: Emitter<StreamState>) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope.launch {
            state.map { number(it) }.distinctUntilChanged().collect { v ->
                emitter.onNext(if (v == null) StreamState.NotAvailable
                    else StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to v))))
            }
        }
        emitter.setCancellable { scope.cancel() }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val density = context.resources.displayMetrics.density
        val widthDp = config.viewSize.first / density - 16f
        val heightDp = config.viewSize.second / density
        scope.launch {
            emitter.onNext(UpdateGraphicConfig(showHeader = false))
            state.map { render(it) }
                .distinctUntilChanged()
                .whileVisible(visibleDataTypeIds, dataTypeId, config.preview)
                .throttle(REDRAW_THROTTLE_MS)
                .collect { face ->
                    val byHeight = heightDp * 0.40f
                    val byWidth = widthDp / (CHAR_EM * face.value.length.coerceAtLeast(3))
                    val valueSp = minOf(byHeight, byWidth).coerceIn(16f, 80f)
                    val captionSp = (valueSp * 0.32f).coerceIn(10f, 16f)
                    val subSp = (valueSp * 0.40f).coerceIn(10f, 20f)
                    val small = heightDp < 60f
                    val views = RemoteViews(context.packageName, R.layout.view_field)
                    views.setTextViewText(R.id.tv_caption, face.caption)
                    views.setTextViewText(R.id.tv_value, face.value)
                    views.setTextViewText(R.id.tv_sub, face.sub)
                    views.setTextViewTextSize(R.id.tv_caption, TypedValue.COMPLEX_UNIT_SP, captionSp)
                    views.setTextViewTextSize(R.id.tv_value, TypedValue.COMPLEX_UNIT_SP, valueSp)
                    views.setTextViewTextSize(R.id.tv_sub, TypedValue.COMPLEX_UNIT_SP, subSp)
                    views.setViewVisibility(R.id.tv_caption, if (small) View.GONE else View.VISIBLE)
                    views.setViewVisibility(R.id.tv_sub, if (small || face.sub.isEmpty()) View.GONE else View.VISIBLE)
                    views.setTextColor(R.id.tv_value, context.getColor(R.color.page_text))
                    emitter.updateView(views)
                }
        }
        emitter.setCancellable { scope.cancel() }
    }

    private fun render(s: BikeState): Face {
        val caption = when (kind) {
            Kind.BATTERY -> "BIKE BATTERY"; Kind.POWER -> "RIDER POWER"; Kind.SPEED -> "BIKE SPEED"
            Kind.CADENCE -> "CADENCE"; Kind.ODOMETER -> "ODOMETER"; Kind.LIGHT -> "BIKE LIGHT"
        }
        if (!s.connected) return Face(caption, "--", "bike off")
        return when (kind) {
            Kind.BATTERY  -> Face(caption, s.socPct?.let { "$it %" } ?: "--", if (s.chargerConnected == true) "charging" else "")
            Kind.POWER    -> Face(caption, s.riderPowerW?.let { "$it W" } ?: "--", "")
            Kind.SPEED    -> Face(caption, s.speedKmh?.let { "%.1f".format(it) } ?: "--", "km/h")
            Kind.CADENCE  -> Face(caption, s.cadenceRpm?.let { "$it" } ?: "--", "rpm")
            Kind.ODOMETER -> Face(caption, s.odometerM?.let { "%.1f".format(it / 1000.0) } ?: "--", "km")
            Kind.LIGHT    -> Face(caption, s.lightOn?.let { if (it) "ON" else "OFF" } ?: "--", s.lux?.let { "%.0f lx".format(it) } ?: "")
        }
    }
}
