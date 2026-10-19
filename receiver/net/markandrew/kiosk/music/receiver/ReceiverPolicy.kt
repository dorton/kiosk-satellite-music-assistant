package net.markandrew.kiosk.music.receiver

/** Pure selection/backoff policy: never replace a remembered server silently. */
object ReceiverPolicy {
    fun select(keys: List<String>, remembered: String): String? =
        if (remembered.isNotBlank()) keys.singleOrNull { it == remembered } else keys.singleOrNull()
    fun retryDelay(failures: Int): Long = minOf(60_000L, 1000L shl failures.coerceIn(0, 6))
}
