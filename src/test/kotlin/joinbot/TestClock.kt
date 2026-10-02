package joinbot

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Mutable clock for time-dependent tests. */
class TestClock(var now: Instant = Instant.parse("2026-01-01T00:00:00Z")) : Clock() {
    override fun instant() = now
    override fun getZone() = ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId?) = this
}
