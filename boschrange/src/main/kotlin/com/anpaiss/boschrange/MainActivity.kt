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
import androidx.appcompat.app.AlertDialog
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
    private lateinit var tyreBox: LinearLayout
    private lateinit var tyreList: LinearLayout
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
            "through the BoschData extension (install it and pair the Karoo once from the Bosch Flow app); " +
            "without it the popups ask the % at start, after long stops and at arrival. Every ride goes into the history."))

        root.addView(CheckBox(this).apply {
            text = "boschRange ON: popups and ride recording (off = nothing happens during the ride)"
            isChecked = prefs.enabled
            setOnCheckedChangeListener { _, on -> prefs.enabled = on; refresh() }
        })
        tvBike = label("")
        root.addView(tvBike)
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
                    // The tyre row as on the start popup: tyres on and two pairs at least; a tap here only previews
                    val pairs = if (prefs.tyresEnabled) prefs.tyrePairs else emptyList()
                    val labels = TyrePair.labels(pairs)
                    popup.show(BatteryPopupController.Request("Battery now? (test)", 73, if (prefs.conditionEnabled) Condition.DRY else null, { _, _ -> }, {}, reminder = getString(R.string.reminder_flow_start),
                        tyres = if (pairs.size < 2) emptyList() else pairs.map { it.id to labels.getValue(it.id) }, tyreId = prefs.mountedPairId))
                }, 3_000L)
            }
        })

        root.addView(label("Tyres").apply { setTypeface(typeface, Typeface.BOLD) })
        root.addView(CheckBox(this).apply {
            text = "Tyres ON: every ride records the mounted pair, stats per pair (off = no tyres anywhere)"
            isChecked = prefs.tyresEnabled
            setOnCheckedChangeListener { _, on -> prefs.tyresEnabled = on; refresh() }
        })
        tyreBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tyreList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        tyreBox.addView(tyreList)
        tyreBox.addView(Button(this).apply {
            text = "ADD TYRE PAIR"
            setOnClickListener { startActivity(Intent(this@MainActivity, TyrePairActivity::class.java)) }
        })
        tyreBox.addView(CheckBox(this).apply {
            text = "Dry / wet ON: the start popup asks DRY / WET / MUD, stats per pair split by condition (off = simpler popup)"
            isChecked = prefs.conditionEnabled
            setOnCheckedChangeListener { _, on -> prefs.conditionEnabled = on; refresh() }
        })
        tyreBox.addView(label("When the battery popup appears at the start (no BoschData) it shows the mounted pair: " +
            "a tap on it moves to the next one. s = battery Wh / mechanical Wh: gradient, surface and speed are already " +
            "out of it, what is left is mostly tyres, mode, cold and wet. A few rides are not enough to compare two pairs."))
        root.addView(tyreBox)

        tvRide = label("")
        root.addView(tvRide)
        root.addView(label("History (newest first)").apply { setTypeface(typeface, Typeface.BOLD) })
        tvHistory = label("")
        root.addView(tvHistory)
        root.addView(Button(this).apply { text = "REFRESH"; setOnClickListener { refresh() } })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        tvBike.text = "Bike: " + (BoschRangeExtension.instance?.bikeSummary() ?: "extension not running")
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
        refreshTyres(rides)
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

    private fun refreshTyres(rides: List<RideRecord>) {
        tyreBox.visibility = if (prefs.tyresEnabled) View.VISIBLE else View.GONE
        tyreList.removeAllViews()
        if (!prefs.tyresEnabled) return
        val pairs = prefs.tyrePairs
        if (pairs.isEmpty()) { tyreList.addView(label("No tyre pair yet.")); return }
        val labels = TyrePair.labels(pairs)
        val stats = TyreStats.of(rides).associateBy { it.pairId }
        for (p in pairs) {
            val mounted = p.id == prefs.mountedPairId
            val st = stats[p.id]
            val line = buildString {
                append(if (mounted) "● " else "○ "); append(labels.getValue(p.id)); if (mounted) append("  (mounted)")
                append("\nFront: ${p.front.brand} ${p.front.full()}\nRear: ${p.rear.brand} ${p.rear.full()}\n")
                append(when {
                    st == null -> "No ride yet"
                    !st.enough -> "${st.rides} rides, ${"%.0f".format(st.km)} km · ${st.measured} measured, not enough data yet (${TyreStats.MIN_RIDES} needed)"
                    else -> "${st.rides} rides, ${"%.0f".format(st.km)} km · s ${"%.2f".format(st.s)}" +
                        (st.sSpread?.let { " ± ${"%.2f".format(it)}" } ?: "") +
                        (st.pctPer1000m?.let { " · ${"%.1f".format(it)} % per 1000 m" } ?: "") +
                        (if (!prefs.conditionEnabled) "" else
                            (st.sDry?.let { " · dry ${"%.2f".format(it)}" } ?: "") + (st.sWet?.let { " · wet ${"%.2f".format(it)}" } ?: ""))
                })
            }
            tyreList.addView(label(line))
            tyreList.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                if (!mounted) addView(Button(this@MainActivity).apply {
                    text = "MOUNT"; setOnClickListener { prefs.mountedPairId = p.id; refresh() }
                })
                addView(Button(this@MainActivity).apply {
                    text = "DELETE"
                    setOnClickListener {
                        AlertDialog.Builder(this@MainActivity)
                            .setMessage("Delete ${labels.getValue(p.id)}? The rides keep their tyres and stay in the history.")
                            .setPositiveButton("DELETE") { _, _ ->
                                prefs.tyrePairs = prefs.tyrePairs.filter { it.id != p.id }
                                if (mounted) prefs.mountedPairId = null
                                refresh()
                            }
                            .setNegativeButton("CANCEL", null).show()
                    }
                })
            })
        }
    }

    private fun label(text: String) = TextView(this).apply { this.text = text; textSize = 14f; setPadding(0, 8, 0, 8) }
}
