package com.atmospath.platform.llm.nl2opt;

/**
 * A geocoded coordinate resolved from a place name via the risk engine's
 * {@code /places/search} endpoint (backed by Nominatim or Open-Meteo).
 */
public record GeoPoint(double latitude, double longitude, String displayName, String source) {
}
