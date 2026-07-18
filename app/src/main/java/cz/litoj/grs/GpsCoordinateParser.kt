package cz.litoj.grs

/**
 * Represents GPS coordinates with latitude and longitude in decimal degrees
 */
data class GpsCoordinates(
    val latitude: Double,
    val longitude: Double,
) {
    fun latitudeString(format: CoordinateFormat): String =
        formatCoordinate(latitude, isLatitude = true, format)

    fun longitudeString(format: CoordinateFormat): String =
        formatCoordinate(longitude, isLatitude = false, format)

    private fun formatCoordinate(decimal: Double, isLatitude: Boolean, format: CoordinateFormat): String {
        return when (format) {
            CoordinateFormat.DEGREES, CoordinateFormat.AUTO ->
                String.format(java.util.Locale.US, "%.6f", decimal)
            CoordinateFormat.DEGREES_MINUTES ->
                convertDecimalToDegreesMinutes(decimal, isLatitude)
            CoordinateFormat.DEGREES_MINUTES_SECONDS ->
                convertDecimalToDms(decimal, isLatitude)
        }
    }

    private fun convertDecimalToDegreesMinutes(decimal: Double, isLatitude: Boolean): String {
        val absolute = kotlin.math.abs(decimal)
        val degrees = absolute.toInt()
        val minutes = (absolute - degrees) * 60
        return String.format(java.util.Locale.US, "%d°%.3f'", degrees, minutes)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun convertDecimalToDms(decimal: Double, isLatitude: Boolean): String {
        val absolute = kotlin.math.abs(decimal)
        val degrees = absolute.toInt()
        val minutesFull = (absolute - degrees) * 60
        val minutes = minutesFull.toInt()
        val seconds = (minutesFull - minutes) * 60
        return String.format(java.util.Locale.US, "%d°%d'%05.2f\"", degrees, minutes, seconds)
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
     * Try to parse GPS coordinates from a string containing text.
     * Uses fuzzy matching to handle OCR errors.
     * Returns null if no valid coordinates are found.
     */
    fun parseFromText(text: String): ParsedResult? {
        val normalized = normalizeOcrText(text)

        // Each format is handled by exactly one parser — no fallbacks.
        parseDegreesMinutesSeconds(normalized)?.let { return ParsedResult(it, CoordinateFormat.DEGREES_MINUTES_SECONDS) }
        parseDegreesMinutes(normalized)?.let { return ParsedResult(it, CoordinateFormat.DEGREES_MINUTES) }
        parseDecimalDegrees(normalized)?.let { return ParsedResult(it, CoordinateFormat.DEGREES) }

        return null
    }

    /**
     * Fix common OCR errors in coordinate text.
     */
    private fun normalizeOcrText(text: String): String {
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

        return result
    }

    /**
     * Extract latitude from text using fuzzy matching.
     * Looks for N or S followed by/near coordinate-like numbers.
     */
    private fun extractLatitude(text: String): Double? {
        return extractCoordinate(text, isLatitude = true)
    }

    /**
     * Extract longitude from text using fuzzy matching.
     * Looks for E or W followed by/near coordinate-like numbers.
     */
    private fun extractLongitude(text: String): Double? {
        return extractCoordinate(text, isLatitude = false)
    }

    /**
     * Extract a coordinate value from text.
     * Looks for a direction letter (N/S/E/W) and extracts the number adjacent to it.
     * Uses lastIndexOf to avoid matching letters inside words like "inReach".
     */
    private fun extractCoordinate(text: String, isLatitude: Boolean): Double? {
        val directions = if (isLatitude) listOf("N", "S") else listOf("E", "W")
        val maxValue = if (isLatitude) 90.0 else 180.0

        for (direction in directions) {
            // Find the LAST occurrence of the direction letter as a standalone character
            // (not part of a word like "inReach" or "GARMIN")
            val regex = if (isLatitude) {
                Regex("""(?<![a-zA-Z])[NS](?![a-zA-Z])""")
            } else {
                Regex("""(?<![a-zA-Z])[EW](?![a-zA-Z])""")
            }

            val matches = regex.findAll(text).toList()
            val dirMatch = matches.lastOrNull { it.value.equals(direction, ignoreCase = true) } ?: continue
            val dirIndex = dirMatch.range.first

            val before = text.substring(0, dirIndex)
            val after = text.substring(dirIndex + 1)

            // Try to find a coordinate number after the direction: "N 50°10.0502" or "N50.123"
            // Trim to coordinate characters only so we don't match numbers far away
            // (prevents false matches from normalized words like "GARM1N")
            val numberAfter = findCoordinateNumber(trimToCoordinate(after, fromEnd = false), maxValue)
            if (numberAfter != null) {
                val value = if (direction == "S" || direction == "W") -numberAfter else numberAfter
                if (kotlin.math.abs(value) <= maxValue) return value
            }

            // Try to find a coordinate number before the direction: "50°10.0502 N"
            val numberBefore = findCoordinateNumber(trimToCoordinate(before, fromEnd = true), maxValue)
            if (numberBefore != null) {
                val value = if (direction == "S" || direction == "W") -numberBefore else numberBefore
                if (kotlin.math.abs(value) <= maxValue) return value
            }
        }

        return null
    }

    /**
     * Trim text to only include coordinate-like characters (digits, dots, degrees, quotes, spaces).
     * Stops at the first non-coordinate character (letter), ensuring the coordinate number
     * is directly adjacent to the direction letter with only whitespace between them.
     *
     * @param fromEnd If true, trims from the end (for prefix format: number before direction letter).
     *               If false, trims from the start (for suffix format: number after direction letter).
     */
    private fun trimToCoordinate(text: String, fromEnd: Boolean): String {
        val validChars = charArrayOf('0','1','2','3','4','5','6','7','8','9','.','°','\'','"',' ')
        if (fromEnd) {
            val end = text.indexOfLast { it !in validChars }
            return if (end == -1) text else text.substring(end + 1)
        } else {
            val start = text.indexOfFirst { it !in validChars }
            return if (start == -1) text else text.substring(0, start)
        }
    }

    /**
     * Find a coordinate number in text.
     * Handles DMS (50°10'30"), degrees-minutes with ° separator (50°10.050, 50°10'),
     * degrees-minutes with space (50 10.050), plain decimal (50.123), and concatenated DM (5010.050).
     *
     * @param findLast If true, returns the last valid match instead of the first.
     *                 Used when searching for a coordinate number before a direction letter.
     */
    private fun findCoordinateNumber(text: String, maxValue: Double, allowDm: Boolean = true, findLast: Boolean = false): Double? {
        // 1. DMS with °, ', " separators: 50°10'30" or 50° 10' 30.5"
        // Tried first because it's the most specific format
        val dmsPattern = Regex("""(\d{1,3})°\s*(\d{1,2})'\s*(\d{1,2}(?:\.\d+)?)""")
        val dmsMatches = dmsPattern.findAll(text).toList()
        for (match in if (findLast) dmsMatches.asReversed() else dmsMatches) {
            val deg = match.groupValues[1].toInt()
            val min = match.groupValues[2].toInt()
            val sec = match.groupValues[3].toDouble()
            val value = deg + min / 60.0 + sec / 3600.0
            if (value <= maxValue) return value
        }

        // 2. Degrees-minutes with ° separator and decimal minutes: 50°10.050 or 50° 10.050'
        val dmDegreePattern = Regex("""(\d{1,3})°\s*(\d{1,2})\.(\d{1,4})""")
        val dmDegreeMatches = dmDegreePattern.findAll(text).toList()
        for (match in if (findLast) dmDegreeMatches.asReversed() else dmDegreeMatches) {
            val deg = match.groupValues[1].toInt()
            val min = "${match.groupValues[2]}.${match.groupValues[3]}".toDouble()
            val value = deg + min / 60.0
            if (value <= maxValue) return value
        }

        // 3. Degrees-minutes with ° and ' separators (no decimal minutes): 50°10' or 50° 10'
        val dmMinutePattern = Regex("""(\d{1,3})°\s*(\d{1,2})'""")
        val dmMinuteMatches = dmMinutePattern.findAll(text).toList()
        for (match in if (findLast) dmMinuteMatches.asReversed() else dmMinuteMatches) {
            val deg = match.groupValues[1].toInt()
            val min = match.groupValues[2].toDouble()
            val value = deg + min / 60.0
            if (value <= maxValue) return value
        }

        // 4. Degrees-minutes with space separator: 50 10.050
        val dmSpacePattern = Regex("""(\d{1,3})\s+(\d{1,2})\.(\d{1,4})""")
        val dmSpaceMatches = dmSpacePattern.findAll(text).toList()
        for (match in if (findLast) dmSpaceMatches.asReversed() else dmSpaceMatches) {
            val deg = match.groupValues[1].toInt()
            val min = "${match.groupValues[2]}.${match.groupValues[3]}".toDouble()
            val value = deg + min / 60.0
            if (value <= maxValue) return value
        }

        // 5. Plain decimal degrees: XX.XXXXXX
        val decimalPattern = Regex("""(\d{1,3})\.(\d{1,6})""")
        val decimalMatches = decimalPattern.findAll(text).toList()
        for (match in if (findLast) decimalMatches.asReversed() else decimalMatches) {
            val value = match.value.toDoubleOrNull()
            if (value != null && value <= maxValue) return value
        }

        if (allowDm) {
            // 6. Concatenated DM: XXDD.XXX (e.g., 5010.050 → 50° 10.050')
            val concatDmPattern = Regex("""(\d{2,3})(\d{2})\.(\d{1,4})""")
            val concatDmMatches = concatDmPattern.findAll(text).toList()
            for (match in if (findLast) concatDmMatches.asReversed() else concatDmMatches) {
                val deg = match.groupValues[1].toInt()
                val min = "${match.groupValues[2]}.${match.groupValues[3]}".toDouble()
                val value = deg + min / 60.0
                if (value <= maxValue) return value
            }
        }

        return null
    }

    // --- Format parsers (each format handled by exactly one parser) ---

    /**
     * Parse decimal degrees with direction letters.
     * Handles suffix/prefix and normal/flipped order.
     * Requires a decimal point to distinguish from DM/DMS formats.
     */
    private fun parseDecimalDegrees(text: String): GpsCoordinates? {
        return tryParsePair(text, """\d+\.\d+""") { it.toDoubleOrNull() }
    }

    /**
     * Parse degrees-minutes with direction letters.
     * Handles ° separator with optional ' and decimal minutes.
     * Handles suffix/prefix and normal/flipped order.
     */
    private fun parseDegreesMinutes(text: String): GpsCoordinates? {
        return tryParsePair(text, """\d+°\s*\d+(?:\.\d+)?'?""") { parseDmNumber(it) }
    }

    /**
     * Parse degrees-minutes-seconds with direction letters.
     * Handles °, ', " separators.
     * Handles suffix/prefix and normal/flipped order.
     */
    private fun parseDegreesMinutesSeconds(text: String): GpsCoordinates? {
        return tryParsePair(text, """\d+°\s*\d+'\s*\d+(?:\.\d+)?${'"'}""") { parseDmsNumber(it) }
    }

    /**
     * Try to find a pair of coordinates with direction letters.
     * Tries suffix (direction before number) then prefix (number before direction).
     * Determines lat/lon from the direction letters, supporting both normal and flipped order.
     */
    private fun tryParsePair(
        text: String,
        numberPattern: String,
        parseNumber: (String) -> Double?,
    ): GpsCoordinates? {
        // Suffix: direction before number — "N 50.123 E 14.456" or "E 14.456 N 50.123"
        Regex("""([NSEW])\s*($numberPattern)\s*[,;]?\s*([NSEW])\s*($numberPattern)""", RegexOption.IGNORE_CASE)
            .find(text)?.let { match ->
                tryBuildCoords(
                    match.groupValues[1], match.groupValues[2],
                    match.groupValues[3], match.groupValues[4],
                    parseNumber,
                )?.let { return it }
            }

        // Prefix: number before direction — "50.123 N 14.456 E" or "14.456 E 50.123 N"
        Regex("""($numberPattern)\s*([NSEW])\s*[,;]?\s*($numberPattern)\s*([NSEW])""", RegexOption.IGNORE_CASE)
            .find(text)?.let { match ->
                tryBuildCoords(
                    match.groupValues[2], match.groupValues[1],
                    match.groupValues[4], match.groupValues[3],
                    parseNumber,
                )?.let { return it }
            }

        return null
    }

    /**
     * Build coordinates from two direction-number pairs.
     * Determines which is lat (N/S) and which is lon (E/W) from the direction letters.
     */
    private fun tryBuildCoords(
        dir1: String, num1Str: String,
        dir2: String, num2Str: String,
        parseNumber: (String) -> Double?,
    ): GpsCoordinates? {
        val d1 = dir1.uppercase()
        val d2 = dir2.uppercase()
        val isLat1 = d1 == "N" || d1 == "S"
        val isLat2 = d2 == "N" || d2 == "S"
        if (isLat1 == isLat2) return null // Both lat or both lon — invalid

        val v1 = parseNumber(num1Str) ?: return null
        val v2 = parseNumber(num2Str) ?: return null

        val lat = if (isLat1) applyDirection(v1, d1) else applyDirection(v2, d2)
        val lon = if (isLat1) applyDirection(v2, d2) else applyDirection(v1, d1)

        if (!isValidLatLon(lat, lon)) return null
        return GpsCoordinates(lat, lon)
    }

    private fun applyDirection(value: Double, direction: String): Double {
        return if (direction == "S" || direction == "W") -kotlin.math.abs(value) else kotlin.math.abs(value)
    }

    private fun parseDmNumber(text: String): Double? {
        val pattern = Regex("""(\d+)°\s*(\d+(?:\.\d+)?)'?""")
        val match = pattern.matchEntire(text.trim()) ?: return null
        val deg = match.groupValues[1].toInt()
        val min = match.groupValues[2].toDouble()
        return deg + min / 60.0
    }

    private fun parseDmsNumber(text: String): Double? {
        val pattern = Regex("""(\d+)°\s*(\d+)'\s*(\d+(?:\.\d+)?)"""")
        val match = pattern.matchEntire(text.trim()) ?: return null
        val deg = match.groupValues[1].toInt()
        val min = match.groupValues[2].toInt()
        val sec = match.groupValues[3].toDouble()
        return deg + min / 60.0 + sec / 3600.0
    }

    // --- Single value parsers (for manual editing) ---

    fun parseLatitude(text: String, format: CoordinateFormat): Double? {
        val cleaned = text.trim()
        when (format) {
            CoordinateFormat.DEGREES, CoordinateFormat.AUTO -> parseLatDecimal(cleaned)?.let { return it }
            CoordinateFormat.DEGREES_MINUTES -> parseLatDm(cleaned)?.let { return it }
            CoordinateFormat.DEGREES_MINUTES_SECONDS -> parseLatDms(cleaned)?.let { return it }
        }
        return parseLatDecimal(cleaned) ?: parseLatDm(cleaned) ?: parseLatDms(cleaned) ?: extractLatitude(cleaned)
    }

    fun parseLongitude(text: String, format: CoordinateFormat): Double? {
        val cleaned = text.trim()
        when (format) {
            CoordinateFormat.DEGREES, CoordinateFormat.AUTO -> parseLonDecimal(cleaned)?.let { return it }
            CoordinateFormat.DEGREES_MINUTES -> parseLonDm(cleaned)?.let { return it }
            CoordinateFormat.DEGREES_MINUTES_SECONDS -> parseLonDms(cleaned)?.let { return it }
        }
        return parseLonDecimal(cleaned) ?: parseLonDm(cleaned) ?: parseLonDms(cleaned) ?: extractLongitude(cleaned)
    }

    private fun parseLatDecimal(text: String): Double? {
        val pattern = Regex("""(-?\d+\.\d+)\s*[NS]?""")
        val match = pattern.find(text) ?: return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        val isSouth = text.contains("S", ignoreCase = true)
        val lat = if (isSouth) -kotlin.math.abs(value) else kotlin.math.abs(value)
        return if (lat in -90.0..90.0) lat else null
    }

    private fun parseLatDm(text: String): Double? {
        val pattern = Regex("""(\d+)\s+(\d+\.?\d*)\s*([NS])""")
        val match = pattern.find(text) ?: return null
        val lat = convertDmToDecimal(match.groupValues[1].toInt(), match.groupValues[2].toDouble(), match.groupValues[3])
        return if (lat in -90.0..90.0) lat else null
    }

    private fun parseLatDms(text: String): Double? {
        val pattern = Regex("""(\d+)°(\d+)'(\d+\.?\d*)"\s*([NS])""")
        val match = pattern.find(text) ?: return null
        val lat = convertDmsToDecimal(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toDouble(), match.groupValues[4])
        return if (lat in -90.0..90.0) lat else null
    }

    private fun parseLonDecimal(text: String): Double? {
        val pattern = Regex("""(-?\d+\.\d+)\s*[EW]?""")
        val match = pattern.find(text) ?: return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        val isWest = text.contains("W", ignoreCase = true)
        val lon = if (isWest) -kotlin.math.abs(value) else kotlin.math.abs(value)
        return if (lon in -180.0..180.0) lon else null
    }

    private fun parseLonDm(text: String): Double? {
        val pattern = Regex("""(\d+)\s+(\d+\.?\d*)\s*([EW])""")
        val match = pattern.find(text) ?: return null
        val lon = convertDmToDecimal(match.groupValues[1].toInt(), match.groupValues[2].toDouble(), match.groupValues[3])
        return if (lon in -180.0..180.0) lon else null
    }

    private fun parseLonDms(text: String): Double? {
        val pattern = Regex("""(\d+)°(\d+)'(\d+\.?\d*)"\s*([EW])""")
        val match = pattern.find(text) ?: return null
        val lon = convertDmsToDecimal(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toDouble(), match.groupValues[4])
        return if (lon in -180.0..180.0) lon else null
    }

    private fun convertDmToDecimal(degrees: Int, minutes: Double, direction: String): Double {
        var decimal = degrees + minutes / 60.0
        if (direction == "S" || direction == "W") decimal = -decimal
        return decimal
    }

    private fun convertDmsToDecimal(degrees: Int, minutes: Int, seconds: Double, direction: String): Double {
        var decimal = degrees + minutes / 60.0 + seconds / 3600.0
        if (direction == "S" || direction == "W") decimal = -decimal
        return decimal
    }

    private fun isValidLatLon(lat: Double, lon: Double): Boolean =
        lat in -90.0..90.0 && lon in -180.0..180.0

    data class ParsedResult(
        val coordinates: GpsCoordinates,
        val detectedFormat: CoordinateFormat,
    )
}
