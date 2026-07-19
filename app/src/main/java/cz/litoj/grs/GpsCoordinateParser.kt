package cz.litoj.grs

import cz.litoj.grs.GpsCoordinateParser.parseCoordinateNumber


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
        formatCoordinate(latitude, isLatitude = true, format)

    fun longitudeString(format: CoordinateFormat): String =
        formatCoordinate(longitude, isLatitude = false, format)

    private fun formatCoordinate(
        decimal: Double,
        isLatitude: Boolean,
        format: CoordinateFormat
    ): String {
        return when (format) {
            CoordinateFormat.DEGREES, CoordinateFormat.AUTO ->
                String.format(java.util.Locale.US, "%.6f", decimal)

            CoordinateFormat.DEGREES_MINUTES ->
                convertDecimalToDegreesMinutes(decimal, isLatitude)

            CoordinateFormat.DEGREES_MINUTES_SECONDS ->
                convertDecimalToDms(decimal, isLatitude)
        }
    }

    private fun convertDecimalToDegreesMinutes(
        decimal: Double,
        isLatitude: Boolean
    ): String {
        val absolute = kotlin.math.abs(decimal)
        val degrees = absolute.toInt()
        val minutes = (absolute - degrees) * 60
        return String.format(java.util.Locale.US, "%d°%.3f'", degrees, minutes)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun convertDecimalToDms(
        decimal: Double,
        isLatitude: Boolean
    ): String {
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

enum class CoordinateFormat(val displayName: String) {
    AUTO("Auto"),
    DEGREES("Deg"),
    DEGREES_MINUTES("D° M'"),
    DEGREES_MINUTES_SECONDS("Full"),
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
        result = result.replace("t", "4")
        // S/h → 5 when in number context (but preserve S as direction at end of coordinate)
        result = result.replace(Regex("""(?<=[\d.])[Sh](?=\d)"""), "5")
        result = result.replace("B", "8")
        result = result.replace("g", "9")

        // Remove random spaces OCR inserted within coordinate numbers
        // (e.g. "5 0. 1 2 3" → "50.123"), while preserving spaces between
        // direction letters and numbers ("N 50.123" stays unchanged)
        result = result.replace(Regex("""(?<=[0-9.°'"])\s+(?=[0-9.°'"])"""), "")

        return result
    }

    /**
     * Combined coordinate-number pattern matching all 3 formats:
     * - Decimal:  50.123456
     * - DM:       50°10.050, 50°10.050', 50°10'
     * - DMS:      50°10'30.5", 50° 10' 30.5"
     *
     * Spaces between DMS/DM components (°, ', ") are handled by `\s*`.
     */
    private val COORD_NUMBER =
        """\d{1,3}(?:\.\d+°?|°\d{1,2}(?:\.\d+)?'?(?:\d{1,2}(?:\.\d+)?")?)"""

    /**
     * Master regex with 4 branches covering all combinations of
     * side (suffix/prefix) × order (lat-first/lon-first).
     *
     * Using [NS] and [EW] separately makes each branch self-validating:
     * - Same-type pairs (N…N, E…E) fail at the regex level
     * - Mixed positions (one suffix, one prefix) fail all 4 branches
     *
     * `(?<![a-zA-Z])` / `(?![a-zA-Z])` ensure the coordinate pair
     * isn't directly connected to other text.
     *
     * Groups per branch (4 each, 16 total):
     * - Branch 1 (suffix, lat-lon):  1=dir₁  2=num₁  3=dir₂  4=num₂
     * - Branch 2 (suffix, lon-lat):  5=dir₁  6=num₁  7=dir₂  8=num₂
     * - Branch 3 (prefix, lat-lon):  9=num₁ 10=dir₁ 11=num₂ 12=dir₂
     * - Branch 4 (prefix, lon-lat): 13=num₁ 14=dir₁ 15=num₂ 16=dir₂
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

    /**
     * Try to parse GPS coordinates from a string containing text.
     * Uses a single master regex to match both coordinates at once,
     * then validates the matched groups.
     * Returns null if no valid coordinates are found.
     */
    fun parseFromText(text: String): GpsCoordinates? {
        val normalized = normalizeOcrText(text)
        val match = MASTER_PATTERN.find(normalized) ?: return null

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

    /**
     * Holds the extracted direction letters, number strings, and whether lat came first.
     */
    private data class MatchGroups(
        val dir1: String,
        val num1Str: String,
        val dir2: String,
        val num2Str: String,
        val latFirst: Boolean,
    )

    /**
     * Extract dir1/num1/dir2/num2 and whether lat came first from a master-regex match.
     * Returns null if no branch matched (shouldn't happen).
     */
    private fun extractMatchGroups(match: MatchResult): MatchGroups? {
        val g = match.groups
        return when {
            // Branch 1 (suffix, lat-lon): groups 1-4 = dir₁,num₁,dir₂,num₂
            g[1] != null -> MatchGroups(
                g[1]!!.value,
                g[2]!!.value,
                g[3]!!.value,
                g[4]!!.value,
                latFirst = true
            )
            // Branch 2 (suffix, lon-lat): groups 5-8 = dir₁,num₁,dir₂,num₂
            g[5] != null -> MatchGroups(
                g[5]!!.value,
                g[6]!!.value,
                g[7]!!.value,
                g[8]!!.value,
                latFirst = false
            )
            // Branch 3 (prefix, lat-lon): groups 9-12 = num₁,dir₁,num₂,dir₂ → reorder
            g[9] != null -> MatchGroups(
                g[10]!!.value,
                g[9]!!.value,
                g[12]!!.value,
                g[11]!!.value,
                latFirst = true
            )
            // Branch 4 (prefix, lon-lat): groups 13-16 = num₁,dir₁,num₂,dir₂ → reorder
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

    /**
     * Detect coordinate format from a matched number string.
     */
    private fun detectFormat(numberStr: String): CoordinateFormat = when {
        numberStr.contains("\"") -> CoordinateFormat.DEGREES_MINUTES_SECONDS
        numberStr.contains("°") -> CoordinateFormat.DEGREES_MINUTES
        else -> CoordinateFormat.DEGREES
    }

    /**
     * Parse a coordinate number string to decimal degrees.
     * Dispatches to the appropriate parser based on the detected format.
     */
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

    fun parseLatitude(
        text: String,
        @Suppress("UNUSED_PARAMETER") format: CoordinateFormat
    ): Double? =
        parseSingleValue(text, isLatitude = true)

    fun parseLongitude(
        text: String,
        @Suppress("UNUSED_PARAMETER") format: CoordinateFormat
    ): Double? =
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
