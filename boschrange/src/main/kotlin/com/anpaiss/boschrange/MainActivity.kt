package com.anpaiss.boschrange

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Settings, permissions, the open ride and the last rides of the history. Built in code, no layout XML. */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var tvBike: TextView
    private lateinit var tvOverlay: TextView
    private lateinit var tvStorage: TextView
    private lateinit var tvRide: TextView
    private lateinit var tvHistory: TextView
    private val thresholds = listOf(10, 15, 20, 25, 30)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val pad = (12 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

        root.addView(TextView(this).apply {
            text = "boschRange ${BuildConfig.VERSION_NAME}"
            textSize = 22f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.parseColor("#FF5823"))
        })
        root.addView(label("Bosch battery at arrival, predicted from the route profile. The battery % comes from the bike " +
            "over Bluetooth (Bosch Live Data Interface: pair the Karoo once from Flow → Add component → Accessories); " +
            "without the bike the popups ask it at start, after long stops and at arrival. Every ride goes into the history."))

        root.addView(CheckBox(this).apply {
            text = "boschRange ON: bike link, popups and ride recording (off = nothing happens during the ride)"
            isChecked = prefs.enabled
            setOnCheckedChangeListener { _, on -> prefs.enabled = on; BoschRangeExtension.instance?.onEnabledChanged(on); refresh() }
        })
        tvBike = label("")
        root.addView(tvBike)
        if (android.os.Build.VERSION.SDK_INT >= 31) root.addView(Button(this).apply {
            text = "GRANT BLUETOOTH (bike link)"
            setOnClickListener { requestPermissions(BoschRangeExtension.BLUETOOTH_PERMISSIONS, 2) }
        })
        root.addView(CheckBox(this).apply {
            text = "PowerMore 250 Wh mounted (1000 Wh instead of 750)"
            isChecked = prefs.powerMore
            setOnCheckedChangeListener { _, on -> prefs.powerMore = on }
        })
        root.addView(label("Alert when the arrival estimate drops below:"))
        root.addView(Spinner(this).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, thresholds.map { "$it %" })
            setSelection(thresholds.indexOf(prefs.alertThresholdPct).coerceAtLeast(0), false)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { prefs.alertThresholdPct = thresholds[position] }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        })

        tvOverlay = label("")
        root.addView(tvOverlay)
        root.addView(Button(this).apply {
            text = "GRANT OVERLAY PERMISSION"
            setOnClickListener { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        })
        tvStorage = label("")
        root.addView(tvStorage)
        root.addView(Button(this).apply {
            text = "GRANT STORAGE (offline maps)"
            setOnClickListener { requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 1) }
        })
        root.addView(Button(this).apply {
            text = "TEST POPUP (shows in 3 s)"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) { refresh(); return@setOnClickListener }
                val popup = BatteryPopupController(applicationContext, BoschRangeLogger(applicationContext, announce = false))
                window.decorView.postDelayed({
                    BoschRangeExtension.instance?.playPopupSound()
                    popup.show(BatteryPopupController.Request("Battery now? (test)", 73, Condition.DRY, { _, _ -> }, {}, reminder = getString(R.string.reminder_flow_start)))
                }, 3_000L)
            }
        })

        tvRide = label("")
        root.addView(tvRide)
        root.addView(label("History (newest first)").apply { setTypeface(typeface, Typeface.BOLD) })
        tvHistory = label("")
        root.addView(tvHistory)
        root.addView(Button(this).apply { text = "REFRESH"; setOnClickListener { refresh() } })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() { super.onResume(); refresh() }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2) BoschRangeExtension.instance?.onEnabledChanged(prefs.enabled)
        refresh()
    }

    private fun refresh() {
        tvBike.text = "Bike (LDI): " + (BoschRangeExtension.instance?.bikeSummary() ?: "extension not running")
        tvOverlay.text = "Overlay permission: " + if (Settings.canDrawOverlays(this)) "granted ✓" else "MISSING — the popups cannot appear"
        val storage = checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        tvStorage.text = "Storage permission: " + if (storage) "granted ✓ (surface from the offline maps)" else "missing — surface via Overpass only"
        val ext = BoschRangeExtension.instance
        tvRide.text = when {
            ext == null -> "Extension not running"
            else -> ext.openRideSummary() ?: "No ride open. Capacity ${prefs.capacityWh.toInt()} Wh, s = ${prefs.sCoefficient}, rider ${prefs.riderKg.toInt()} kg"
        }
        val history = RideHistory(this)
        val rides = history.load().asReversed()
        val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.ITALY)
        tvHistory.text = if (rides.isEmpty()) "No rides yet.\n${history.historyFile.absolutePath}" else
            rides.take(12).joinToString("\n") { r ->
                val real = r.whReal
                "${fmt.format(Date(r.startMs))}  ${r.startPct}${if (r.startAssumed) "?" else ""} → ${r.arrivalPct?.toString() ?: "–"} %  " +
                    "${"%.1f".format(r.distanceKm)} km  D+ ${r.ascentM.toInt()} m  est ${"%.0f".format(r.whEstimated)} Wh" +
                    (real?.let { "  real ${"%.0f".format(it)} Wh  s ${"%.2f".format(it / r.whMech.coerceAtLeast(1.0))}" } ?: "") +
                    (r.condition?.let { "  ${it.name.lowercase()}" } ?: "") +
                    (if (r.routeUnclassifiedPct > 0.0) "  unk ${r.routeUnclassifiedPct.toInt()}%" else "") +
                    (if (r.validForRegression) "" else "  [excluded]")
            } + "\n${rides.size} rides, ${rides.count { it.validForRegression }} valid for the regression (start confirmed and arrival entered)\n${history.historyFile.absolutePath}"
    }

    private fun label(text: String) = TextView(this).apply { this.text = text; textSize = 14f; setPadding(0, 8, 0, 8) }
}
