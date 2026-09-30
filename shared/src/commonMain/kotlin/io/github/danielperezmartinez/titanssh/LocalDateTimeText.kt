package io.github.danielperezmartinez.titanssh

/**
 * [epochMs] as a date and time in the device's time zone, the way the UI
 * shows it: "27/09 16:10", or "27/09/2025 16:10" when it is not in the same
 * year as [nowMs].
 */
expect fun formatLocalDateTime(epochMs: Long, nowMs: Long): String
