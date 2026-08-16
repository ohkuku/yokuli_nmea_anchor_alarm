package com.yokuli.anchorwatch.domain.anchor

data class ParsedCoordinate(val latitude: Double, val longitude: Double)

object CoordinateParser {
    fun parse(value: String): Result<ParsedCoordinate> = runCatching {
        val pieces = value.trim().split(Regex("[,\\s]+"), limit = 3).filter { it.isNotBlank() }
        require(pieces.size == 2) { "Enter latitude and longitude separated by a comma or space." }
        val latitude = pieces[0].toDouble()
        val longitude = pieces[1].toDouble()
        require(latitude.isFinite() && longitude.isFinite()) { "Coordinates must be finite numbers." }
        require(latitude in -90.0..90.0) { "Latitude must be between -90 and 90." }
        require(longitude in -180.0..180.0) { "Longitude must be between -180 and 180." }
        ParsedCoordinate(latitude, longitude)
    }
}
