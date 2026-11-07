package com.anpaiss.boschrange

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** A new tyre pair: front and rear from the catalog (brand → model → size → casing → compound) or typed in. */
class TyrePairActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        val catalog = TyreCatalog.get(this)
        val pad = (12 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        fun heading(s: String) = TextView(this).apply {
            text = s; textSize = 18f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.parseColor("#FF5823")); setPadding(0, 16, 0, 4)
        }

        root.addView(heading("Front"))
        val front = TyrePicker(this, catalog)
        root.addView(front)
        val same = CheckBox(this).apply { text = "Rear: the same tyre"; isChecked = false }
        root.addView(heading("Rear"))
        root.addView(same)
        val rear = TyrePicker(this, catalog)
        root.addView(rear)
        same.setOnCheckedChangeListener { _, on -> rear.visibility = if (on) View.GONE else View.VISIBLE }
        val mount = CheckBox(this).apply { text = "Mounted now"; isChecked = true }
        root.addView(mount)
        val error = TextView(this).apply { setTextColor(Color.parseColor("#C62828")) }
        root.addView(error)
        root.addView(Button(this).apply {
            text = "SAVE PAIR"
            setOnClickListener {
                val f = front.tyre(); val r = if (same.isChecked) f else rear.tyre()
                if (f == null || r == null) { error.text = "Model and width are needed for both tyres."; return@setOnClickListener }
                val pair = TyrePair(System.currentTimeMillis().toString(), f, r)
                prefs.tyrePairs = prefs.tyrePairs + pair
                if (mount.isChecked || prefs.mountedPair == null) prefs.mountedPairId = pair.id
                finish()
            }
        })
        root.addView(TextView(this).apply {
            textSize = 12f; setPadding(0, 16, 0, 0)
            text = "The catalog: the MTB tyres in 27.5\" and 29\" of ${catalog.brands.joinToString(", ") { it.name }}, " +
                "as their own sites list them on 7/11/2026. Casing or compound empty where the brand does not give them. " +
                "A tyre that is not there: choose Other and type it."
        })
        setContentView(ScrollView(this).apply { addView(root) })
    }
}

/** Cascading choice of one tyre; "Other" at the end of the brands turns it into text fields. */
private class TyrePicker(context: Context, private val catalog: TyreCatalog) : LinearLayout(context) {

    private val brand = Spinner(context)
    private val model = Spinner(context)
    private val size = Spinner(context)
    private val casing = Spinner(context)
    private val compound = Spinner(context)
    private val fromCatalog = LinearLayout(context).apply { orientation = VERTICAL }
    private val manual = LinearLayout(context).apply { orientation = VERTICAL; visibility = GONE }
    private val mBrand = field("Brand"); private val mModel = field("Model")
    private val mDiameter = Spinner(context).apply { adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, listOf("29", "27.5")) }
    private val mWidth = field("Width, e.g. 2.40", InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
    private val mCasing = field("Casing (optional)"); private val mCompound = field("Compound (optional)")

    private var variants: List<Tyre> = emptyList()

    init {
        orientation = VERTICAL
        addView(TextView(context).apply { text = "Brand"; textSize = 13f }); addView(brand)
        listOf("Model" to model, "Size" to size, "Casing" to casing, "Compound" to compound).forEach { (l, s) ->
            fromCatalog.addView(TextView(context).apply { text = l; textSize = 13f }); fromCatalog.addView(s)
        }
        addView(fromCatalog)
        manual.addView(mBrand); manual.addView(mModel); manual.addView(mDiameter); manual.addView(mWidth)
        manual.addView(mCasing); manual.addView(mCompound)
        addView(manual)
        fill(brand, catalog.brands.map { it.name } + OTHER) { onBrand() }
    }

    private fun field(hint: String, type: Int = InputType.TYPE_CLASS_TEXT) = EditText(context).apply { this.hint = hint; inputType = type; isSingleLine = true }

    private fun fill(s: Spinner, items: List<String>, onPick: () -> Unit) {
        s.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, items)
        s.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = onPick()
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        s.setSelection(0, false); onPick()
    }

    private fun picked(s: Spinner): String = s.selectedItem as? String ?: ""
    private fun shown(v: String?) = v ?: NONE

    private fun onBrand() {
        val other = picked(brand) == OTHER
        manual.visibility = if (other) VISIBLE else GONE
        fromCatalog.visibility = if (other) GONE else VISIBLE
        if (other) return
        val b = catalog.brands.first { it.name == picked(brand) }
        fill(model, b.models.map { it.name }) { onModel() }
    }

    private fun onModel() {
        val b = catalog.brands.firstOrNull { it.name == picked(brand) } ?: return
        variants = b.models.first { it.name == picked(model) }.variants
        fill(size, variants.map { "${it.diameter}×${it.width}" }.distinct()) { onSize() }
    }

    private fun onSize() {
        val v = variants.filter { "${it.diameter}×${it.width}" == picked(size) }
        fill(casing, v.map { shown(it.casing) }.distinct()) { onCasing() }
    }

    private fun onCasing() {
        val v = variants.filter { "${it.diameter}×${it.width}" == picked(size) && shown(it.casing) == picked(casing) }
        fill(compound, v.map { shown(it.compound) }.distinct()) {}
    }

    /** The chosen tyre, null when "Other" lacks the model or the width. */
    fun tyre(): Tyre? {
        if (picked(brand) == OTHER) {
            val m = mModel.text.toString().trim(); val w = mWidth.text.toString().trim().replace(',', '.')
            if (m.isEmpty() || w.isEmpty()) return null
            return Tyre(mBrand.text.toString().trim().ifEmpty { OTHER }, m, picked(mDiameter), w,
                mCasing.text.toString().trim().ifEmpty { null }, mCompound.text.toString().trim().ifEmpty { null })
        }
        return variants.firstOrNull {
            "${it.diameter}×${it.width}" == picked(size) && shown(it.casing) == picked(casing) && shown(it.compound) == picked(compound)
        }
    }

    companion object {
        const val OTHER = "Other"
        const val NONE = "—"
    }
}
