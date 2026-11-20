package com.hifairy.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PeakTariffTest {
    @Test fun weekdayPeakTurnsToValleyAtNoon() {
        val state = PeakTariff.current(Instant.parse("2026-11-24T01:30:00Z"))!!
        assertTrue(state.peak)
        assertEquals(Instant.parse("2026-11-24T04:00:00Z"), state.nextAt)
    }

    @Test fun weekendValleyCountsDownToMondayMorning() {
        val state = PeakTariff.current(Instant.parse("2026-11-21T02:00:00Z"))!!
        assertFalse(state.peak)
        assertEquals(Instant.parse("2026-11-23T01:00:00Z"), state.nextAt)
    }

    @Test fun nationalHolidayDoesNotShowPeakRate() {
        val state = PeakTariff.current(Instant.parse("2026-10-01T02:00:00Z"))!!
        assertFalse(state.peak)
        assertEquals(Instant.parse("2026-10-08T01:00:00Z"), state.nextAt)
    }

    @Test fun unknownYearIsNotGuessed() {
        assertEquals(null, PeakTariff.current(Instant.parse("2027-01-03T02:00:00Z")))
    }
}
