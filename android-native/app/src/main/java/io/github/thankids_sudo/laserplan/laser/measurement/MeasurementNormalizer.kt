package io.github.thankids_sudo.laserplan.laser.measurement

import io.github.thankids_sudo.laserplan.laser.model.MeasurementType
import io.github.thankids_sudo.laserplan.laser.model.RawReading

/**
 * Unit handling. Converts to SI metres, keeps the original value and unit, and parses typed or streamed readings
 * ("1.234 m", "1,234m", "1234 mm", "12' 3 1/2\"", "3 1/2 in"). Precision is never increased: the value is converted,
 * not rounded to a finer step than the meter gave.
 */
object MeasurementNormalizer {
    private val distanceFactor = mapOf(
        "m" to 1.0, "mm" to 0.001, "cm" to 0.01, "dm" to 0.1,
        "ft" to 0.3048, "in" to 0.0254, "yd" to 0.9144,
    )

    fun canonicalUnit(u: String): String? = when (u.trim().lowercase()) {
        "m", "meter", "metre", "meters", "metres" -> "m"
        "mm", "millimetre", "millimeter" -> "mm"
        "cm", "centimetre", "centimeter" -> "cm"
        "dm" -> "dm"
        "ft", "feet", "foot", "'" -> "ft"
        "in", "inch", "inches", "\"" -> "in"
        "yd", "yard", "yards" -> "yd"
        "m2", "m²", "sqm" -> "m2"
        "ft2", "ft²", "sqft" -> "ft2"
        "m3", "m³" -> "m3"
        "ft3", "ft³" -> "ft3"
        "°", "deg" -> "deg"
        else -> null
    }

    fun typeOf(unit: String): MeasurementType = when (canonicalUnit(unit)) {
        "m2", "ft2" -> MeasurementType.AREA
        "m3", "ft3" -> MeasurementType.VOLUME
        "deg" -> MeasurementType.ANGLE
        null -> MeasurementType.UNKNOWN
        else -> MeasurementType.DISTANCE
    }

    /** Metres for a distance reading; null for an unknown unit, a non-distance reading, or a non-finite value. */
    fun toMetres(value: Double, unit: String): Double? {
        if (!value.isFinite()) return null
        val f = distanceFactor[canonicalUnit(unit) ?: return null] ?: return null
        return value * f
    }

    private fun num(s: String): Double? {
        val t = s.trim()
        val n = if (t.count { it == ',' } == 1 && !t.contains('.')) t.replace(',', '.') else t.replace(",", "")
        return n.toDoubleOrNull()
    }

    private val feetInch = Regex("""^(\d+(?:[.,]\d+)?)\s*(?:'|ft)\s*(?:(\d+(?:[.,]\d+)?)(?:\s+(\d+)\s*/\s*(\d+))?|(\d+)\s*/\s*(\d+))?\s*(?:"|in)?$""")
    private val inchFraction = Regex("""^(?:(\d+)\s+)?(\d+)\s*/\s*(\d+)\s*(?:"|in)$""")
    private val decimalUnit = Regex("""^([+-]?\d+(?:[.,]\d+)*)\s*([a-zA-Z'"²³°]+\d?)?$""")

    /** Parse one reading. A bare number takes [defaultUnit]. Returns null when it is not a reading. */
    fun parse(text: String, defaultUnit: String = "m"): RawReading? {
        val t = text.trim().trimEnd('\r', '\n', '\t').replace(' ', ' ')
        if (t.isEmpty() || t.length > 40) return null
        feetInch.matchEntire(t)?.let { m ->
            val ft = num(m.groupValues[1]) ?: return null
            var inch = 0.0
            if (m.groupValues[2].isNotEmpty()) inch += num(m.groupValues[2]) ?: 0.0
            if (m.groupValues[3].isNotEmpty() && m.groupValues[4].isNotEmpty()) {
                val d = m.groupValues[4].toDouble(); if (d == 0.0) return null
                inch += m.groupValues[3].toDouble() / d
            }
            if (m.groupValues[5].isNotEmpty() && m.groupValues[6].isNotEmpty()) {
                val d = m.groupValues[6].toDouble(); if (d == 0.0) return null
                inch += m.groupValues[5].toDouble() / d
            }
            // Only accept "x ft" forms that really carry a feet mark; "12" alone falls through to decimals.
            return RawReading(ft * 12.0 + inch, "in", MeasurementType.DISTANCE, t)
        }
        inchFraction.matchEntire(t)?.let { m ->
            val whole = m.groupValues[1].takeIf { it.isNotEmpty() }?.toDouble() ?: 0.0
            val d = m.groupValues[3].toDouble(); if (d == 0.0) return null
            return RawReading(whole + m.groupValues[2].toDouble() / d, "in", MeasurementType.DISTANCE, t)
        }
        decimalUnit.matchEntire(t)?.let { m ->
            val v = num(m.groupValues[1]) ?: return null
            val rawUnit = m.groupValues[2].ifEmpty { defaultUnit }
            val unit = canonicalUnit(rawUnit) ?: return null
            return RawReading(v, unit, typeOf(unit), t)
        }
        return null
    }
}
