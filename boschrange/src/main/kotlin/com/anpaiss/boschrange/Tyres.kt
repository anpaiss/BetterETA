package com.anpaiss.boschrange

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One tyre as mounted: from the catalog, or typed by the rider ("Other"). Casing and compound may be unknown. */
data class Tyre(
    val brand: String,
    val model: String,
    val diameter: String,
    val width: String,
    val casing: String?,
    val compound: String?,
) {
    /** "Assegai 29×2.50 EXO+ 3C MaxxTerra": the full name, for the app; the popup shows only the model. */
    fun full(): String = listOfNotNull("$model $diameter×$width", casing, compound).joinToString(" ")

    fun toJson(): JSONObject = JSONObject().put("brand", brand).put("model", model).put("d", diameter).put("w", width)
        .put("casing", casing ?: JSONObject.NULL).put("compound", compound ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject) = Tyre(o.getString("brand"), o.getString("model"), o.getString("d"), o.getString("w"),
            o.optStringOrNull("casing"), o.optStringOrNull("compound"))
    }
}

/** A front and a rear the rider saved; [id] stays the same for the stats when the pair is renamed or re-mounted. */
data class TyrePair(val id: String, val front: Tyre, val rear: Tyre) {
    /** "Assegai / DHR II", or just "Assegai" when both ends are the same model: no brand, everyone knows it (Andrea, 7/11). */
    fun name(): String = if (front.model == rear.model) front.model else "${front.model} / ${rear.model}"

    fun toJson(): JSONObject = JSONObject().put("id", id).put("front", front.toJson()).put("rear", rear.toJson())

    companion object {
        fun fromJson(o: JSONObject) = TyrePair(o.getString("id"), Tyre.fromJson(o.getJSONObject("front")), Tyre.fromJson(o.getJSONObject("rear")))

        fun listToJson(pairs: List<TyrePair>): String = JSONArray().apply { pairs.forEach { put(it.toJson()) } }.toString()
        fun listFromJson(s: String?): List<TyrePair> = runCatching {
            JSONArray(s ?: "[]").let { a -> (0 until a.length()).map { fromJson(a.getJSONObject(it)) } }
        }.getOrDefault(emptyList())

        /**
         * The names shown for [pairs]: the model names, and only where two pairs would
         * read the same, what tells them apart (casing, compound, size), e.g. "Assegai / DHR II · DoubleDown".
         */
        fun labels(pairs: List<TyrePair>): Map<String, String> {
            val out = mutableMapOf<String, String>()
            for ((name, group) in pairs.groupBy { it.name() }) {
                if (group.size == 1) { out[group[0].id] = name; continue }
                val fields = listOf<(Tyre) -> String?>({ it.casing }, { it.compound }, { "${it.diameter}×${it.width}" })
                for (p in group) {
                    val diff = fields.filter { f -> group.map { f(it.front) to f(it.rear) }.distinct().size > 1 }
                        .map { f -> listOfNotNull(f(p.front), f(p.rear)).distinct().joinToString("/") }
                    out[p.id] = if (diff.isEmpty()) name else "$name · ${diff.joinToString(" ")}"
                }
            }
            return out
        }
    }
}

/**
 * The tyres of the official catalogs of the brands (assets/tyres.json, MTB 27.5" and 29",
 * read from the manufacturers' sites on 7/11/2026): brand → model → variants.
 */
class TyreCatalog(val brands: List<Brand>) {
    class Brand(val name: String, val models: List<Model>)
    class Model(val name: String, val variants: List<Tyre>)

    companion object {
        @Volatile private var cached: TyreCatalog? = null

        fun get(context: Context): TyreCatalog = cached ?: synchronized(this) {
            cached ?: parse(context.assets.open("tyres.json").bufferedReader().use { it.readText() }).also { cached = it }
        }

        fun parse(json: String): TyreCatalog {
            val brands = JSONObject(json).getJSONArray("brands")
            return TyreCatalog((0 until brands.length()).map { i ->
                val b = brands.getJSONObject(i); val brand = b.getString("brand")
                val models = b.getJSONArray("models")
                Brand(brand, (0 until models.length()).map { j ->
                    val m = models.getJSONObject(j); val vs = m.getJSONArray("variants")
                    Model(m.getString("model"), (0 until vs.length()).map { k ->
                        val v = vs.getJSONObject(k)
                        Tyre(brand, m.getString("model"), v.getString("d"), v.getString("w"), v.optStringOrNull("casing"), v.optStringOrNull("compound"))
                    })
                })
            })
        }
    }
}

private fun JSONObject.optStringOrNull(k: String): String? = if (isNull(k)) null else optString(k).takeIf { it.isNotBlank() }
