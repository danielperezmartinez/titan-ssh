package io.github.danielperezmartinez.titanssh

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val SAME_YEAR = DateTimeFormatter.ofPattern("dd/MM HH:mm")
private val OTHER_YEAR = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

actual fun formatLocalDateTime(epochMs: Long, nowMs: Long): String {
    val zone = ZoneId.systemDefault()
    val at = Instant.ofEpochMilli(epochMs).atZone(zone)
    val now = Instant.ofEpochMilli(nowMs).atZone(zone)
    return at.format(if (at.year == now.year) SAME_YEAR else OTHER_YEAR)
}
