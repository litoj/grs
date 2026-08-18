package cz.litoj.grs

/**
 * Represents GPS coordinates with latitude and longitude in decimal degrees,
 * along with the coordinate format they were originally detected in.
 */
data class GpsCoordinates(
    val latitude: Double,
    val longitude: Double,
    val format: CoordinateFormat = CoordinateFormat.DEGREES,
) {
    fun latitudeString(format: CoordinateFormat): String =
        formatCoordinate(latitude, format)

    fun longitudeString(format: CoordinateFormat): String =
        formatCoordinate(longitude, format)

    private fun formatCoordinate(
        decimal: Double,
        format: CoordinateFormat
    ): String {
        return when (format) {
            CoordinateFormat.DEGREES, CoordinateFormat.AUTO ->
                String.format(java.util.Locale.US, "%.6f", decimal)

            CoordinateFormat.DEGREES_MINUTES ->
                convertDecimalToDegreesMinutes(decimal)

            CoordinateFormat.DEGREES_MINUTES_SECONDS ->
                convertDecimalToDms(decimal)
        }
    }

    private fun convertDecimalToDegreesMinutes(decimal: Double): String {
        val absolute = kotlin.math.abs(decimal)
        val degrees = absolute.toInt()
        val minutes = (absolute - degrees) * 60
        return String.format(java.util.Locale.US, "%d°%.4f'", degrees, minutes)
    }

    private fun convertDecimalToDms(decimal: Double): String {
        val absolute = kotlin.math.abs(decimal)
        val degrees = absolute.toInt()
        val minutesFull = (absolute - degrees) * 60
        val minutes = minutesFull.toInt()
        val seconds = (minutesFull - minutes) * 60
        return String.format(
            java.util.Locale.US,
            "%d°%d'%05.2f\"",
            degrees,
            minutes,
            seconds
        )
    }
}

enum class CoordinateFormat(@androidx.annotation.StringRes val displayNameRes: Int) {
    AUTO(R.string.format_auto),
    DEGREES(R.string.format_degrees),
    DEGREES_MINUTES(R.string.format_degrees_minutes),
    DEGREES_MINUTES_SECONDS(R.string.format_degrees_minutes_seconds),
}

/**
 * Parser for extracting GPS coordinates from OCR text.
 * Designed to be forgiving of OCR errors (O→0, S→5, B→8, etc.)
 * and to extract latitude and longitude independently from messy text.
 */
object GpsCoordinateParser {

    /**
     * Fix common OCR errors in coordinate text.
     */
    fun normalizeOcrText(text: String): String {
        var result = text

        // Fix common OCR confusions
        result = result.replace("\t", " ")
        result = result.replace(Regex("[DoO]"), "0")
        result = result.replace(Regex("[l|!iI]"), "1")
        result = result.replace("Z", "2")
        // S is 5 only inside numbers (so it can't kill the direction letter)
        result = result.replace(Regex("""(?<=[\d.])S(?=\d)"""), "5")
        result = result.replace("B", "8")
        result = result.replace("g", "9")

        // Collapse spaces inside numbers (keep "N 50.123" untouched)
        result = result.replace(Regex("""(?<=[0-9.°'"])\s+(?=[0-9.°'"])"""), "")

        return result
    }

    /**
     * One coordinate's number part: decimal (`50.123`), D°M' (`50°10.05'`), or D°M'S" (`50°10'30"`).
     *
     * Breakdown:
     * - `\d{1,3}` — 1–3 digit degrees
     * - `(?:\.\d+°?|…)` — either a decimal fraction (optionally followed by ° for decimal degrees like `50.123°`),
     *   or a D°M'/D°M'S" continuation starting with `°`
     */
    private const val COORD_NUMBER =
        """\d{1,3}(?:\.\d+°?|°\d{1,2}(?:\.\d+)?'?(?:\d{1,2}(?:\.\d+)?")?)"""

    /**
     * Finds lat+lon in any order/side layout; rejects same-type pairs.
     *
     * Four branches (each captures 4 groups: dir1, num1, dir2, num2):
     * 1. Suffix, lat-lon:  `N 50.123 E 14.456`  → groups 1–4, latFirst = true
     * 2. Suffix, lon-lat:  `E 14.456 N 50.123`  → groups 5–8, latFirst = false
     * 3. Prefix, lat-lon:  `50.123 N 14.456 E`  → groups 9–12, latFirst = true
     * 4. Prefix, lon-lat:  `14.456 E 50.123 N`  → groups 13–16, latFirst = false
     *
     * Lookbehind/ahead `(?<![a-zA-Z])` … `(?![a-zA-Z])` prevents matching letters
     * inside words (e.g. the “N” in “inReach”).
     */
    private val MASTER_PATTERN = Regex(
        """(?i)(?<![a-zA-Z])(?:""" +
            // 1. Suffix, lat-lon:  N 50.123 E 14.456
            """([NS])\s*($COORD_NUMBER)\s*[,;]?\s*([EW])\s*($COORD_NUMBER)""" +
            """|""" +
            // 2. Suffix, lon-lat:  E 14.456 N 50.123
            """([EW])\s*($COORD_NUMBER)\s*[,;]?\s*([NS])\s*($COORD_NUMBER)""" +
            """|""" +
            // 3. Prefix, lat-lon:  50.123 N 14.456 E
            """($COORD_NUMBER)\s*([NS])\s*[,;]?\s*($COORD_NUMBER)\s*([EW])""" +
            """|""" +
            // 4. Prefix, lon-lat:  14.456 E 50.123 N
            """($COORD_NUMBER)\s*([EW])\s*[,;]?\s*($COORD_NUMBER)\s*([NS])""" +
            """)(?![a-zA-Z])""",
    )

    /** Parse lat+lon from already-normalized text, or null. Call [normalizeOcrText] first. */
    fun parseFromText(text: String): GpsCoordinates? {
        val match = MASTER_PATTERN.find(text) ?: return null

        // Determine which branch matched and extract dir1/num1/dir2/num2
        val groups = extractMatchGroups(match) ?: return null

        // Detect format from the first number string
        val format = detectFormat(groups.num1Str)

        // Both numbers must use the same format
        if (detectFormat(groups.num2Str) != format) return null

        // Parse numbers
        val v1 = parseCoordinateNumber(groups.num1Str) ?: return null
        val v2 = parseCoordinateNumber(groups.num2Str) ?: return null

        // Assign lat/lon based on which came first
        val latDir: String
        val latVal: Double
        val lonDir: String
        val lonVal: Double
        if (groups.latFirst) {
            latDir = groups.dir1; latVal = v1
            lonDir = groups.dir2; lonVal = v2
        } else {
            latDir = groups.dir2; latVal = v2
            lonDir = groups.dir1; lonVal = v1
        }

        val lat = applyDirection(latVal, latDir)
        val lon = applyDirection(lonVal, lonDir)

        if (!isValidLatLon(lat, lon)) return null
        return GpsCoordinates(lat, lon, format)
    }

    /** "This block contains a coordinate" — used only to highlight OCR regions (RAW text: normalization would mangle words into fake tokens). */
    fun looksLikeCoordinate(blockText: String): Boolean =
        LETTER_WITH_NUMBER.containsMatchIn(blockText)

    /**
     * Relaxed number pattern for block classification (not parsing).
     * Accepts ≥2 digits with optional decimals or `° ' "` unit marks.
     * More permissive than [COORD_NUMBER] to avoid false negatives when scanning raw OCR blocks.
     */
    private const val COORDISH_NUMBER = "\\d{2,3}(?:[.,]\\d+|[°']\\d{0,3}(?:[.,]\\d+)?['\"]?[°']?)*"

    /** One direction letter adjacent to one coordinate number (either order). */
    private val LETTER_WITH_NUMBER = Regex(
        """(?<![a-zA-Z])[NSEW]\s*$COORDISH_NUMBER(?![a-zA-Z\d])|""" +
            """(?<![a-zA-Z])$COORDISH_NUMBER\s*[NSEW](?![a-zA-Z\d])""",
        RegexOption.IGNORE_CASE,
    )

    /** Matched direction/number per side + which came first. */
    private data class MatchGroups(
        val dir1: String,
        val num1Str: String,
        val dir2: String,
        val num2Str: String,
        val latFirst: Boolean,
    )

    /** Which side holds latitude (from the matched branch). */
    private fun extractMatchGroups(match: MatchResult): MatchGroups? {
        val g = match.groups
        return when {
            g[1] != null -> MatchGroups(
                g[1]!!.value,
                g[2]!!.value,
                g[3]!!.value,
                g[4]!!.value,
                latFirst = true
            )
            g[5] != null -> MatchGroups(
                g[5]!!.value,
                g[6]!!.value,
                g[7]!!.value,
                g[8]!!.value,
                latFirst = false
            )
            g[9] != null -> MatchGroups(
                g[10]!!.value,
                g[9]!!.value,
                g[12]!!.value,
                g[11]!!.value,
                latFirst = true
            )
            g[13] != null -> MatchGroups(
                g[14]!!.value,
                g[13]!!.value,
                g[16]!!.value,
                g[15]!!.value,
                latFirst = false
            )

            else -> null
        }
    }

    /** Format of a given number string. */
    private fun detectFormat(numberStr: String): CoordinateFormat = when {
        numberStr.contains("\"") -> CoordinateFormat.DEGREES_MINUTES_SECONDS
        numberStr.contains("°") -> CoordinateFormat.DEGREES_MINUTES
        else -> CoordinateFormat.DEGREES
    }

    /** String → decimal degrees, or null if malformed. */
    private fun parseCoordinateNumber(text: String): Double? {
        val trimmed = text.trim()
        return when {
            trimmed.contains("\"") -> parseDmsNumber(trimmed)
            trimmed.contains("°") -> parseDmNumber(trimmed)
            else -> trimmed.toDoubleOrNull()
        }
    }

    private fun applyDirection(value: Double, direction: String): Double {
        return if (direction == "S" || direction == "W") -kotlin.math.abs(value) else kotlin.math.abs(
            value
        )
    }

    private fun parseDmNumber(text: String): Double? {
        val pattern = Regex("""(\d+)°(\d+(?:\.\d+)?)'?""")
        val match = pattern.matchEntire(text.trim()) ?: return null
        val deg = match.groupValues[1].toInt()
        val min = match.groupValues[2].toDouble()
        return deg + min / 60.0
    }

    private fun parseDmsNumber(text: String): Double? {
        val pattern = Regex("""(\d+)°(\d+)'(\d+(?:\.\d+)?)"""")
        val match = pattern.matchEntire(text.trim()) ?: return null
        val deg = match.groupValues[1].toInt()
        val min = match.groupValues[2].toInt()
        val sec = match.groupValues[3].toDouble()
        return deg + min / 60.0 + sec / 3600.0
    }

    // --- Single value parsers (for manual editing) ---

    fun parseLatitude(text: String): Double? =
        parseSingleValue(text, isLatitude = true)

    fun parseLongitude(text: String): Double? =
        parseSingleValue(text, isLatitude = false)

    /**
     * Parse a single coordinate value from text (for manual editing).
     * Extracts the direction letter (if present), removes it, and parses
     * the remaining number with [parseCoordinateNumber] (auto-detecting format).
     */
    private fun parseSingleValue(text: String, isLatitude: Boolean): Double? {
        val cleaned = text.trim()
        val maxValue = if (isLatitude) 90.0 else 180.0

        // Find a standalone direction letter (not part of a word)
        val dirRegex = if (isLatitude) {
            Regex("""(?i)(?<![a-zA-Z])[NS](?![a-zA-Z])""")
        } else {
            Regex("""(?i)(?<![a-zA-Z])[EW](?![a-zA-Z])""")
        }
        val dirMatch = dirRegex.find(cleaned)

        // Remove direction letter to get the number part
        val numPart = if (dirMatch != null) {
            cleaned.removeRange(dirMatch.range).trim()
        } else {
            cleaned
        }

        val value = parseCoordinateNumber(numPart) ?: return null

        val signed = when (dirMatch?.value?.uppercase()) {
            "S", "W" -> -kotlin.math.abs(value)
            "N", "E" -> kotlin.math.abs(value)
            else -> value
        }

        return if (kotlin.math.abs(signed) <= maxValue) signed else null
    }

    private fun isValidLatLon(lat: Double, lon: Double): Boolean =
        lat in -90.0..90.0 && lon in -180.0..180.0

}
