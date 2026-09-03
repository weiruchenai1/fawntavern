package me.rerere.fawntavern.data.diagnostics

import me.rerere.fawntavern.core.diagnostics.DiagnosticsRegion
import me.rerere.fawntavern.core.diagnostics.DiagnosticsRegionDetector
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsRegionDetectorTest {
    @Test
    fun mainlandSignalsRemainMainlandWhenUsingForeignProxy() {
        assertEquals(
            DiagnosticsRegion.MAINLAND_CHINA,
            DiagnosticsRegionDetector.detect(
                localeCountry = "CN",
                timeZoneId = "Asia/Shanghai",
                ipCountry = "US",
            ),
        )
    }

    @Test
    fun mainlandIpAndTimeZoneRouteForeignLocaleToMainland() {
        assertEquals(
            DiagnosticsRegion.MAINLAND_CHINA,
            DiagnosticsRegionDetector.detect(
                localeCountry = "US",
                timeZoneId = "Asia/Shanghai",
                ipCountry = "CN",
            ),
        )
    }

    @Test
    fun foreignSignalsRouteToOtherRegions() {
        assertEquals(
            DiagnosticsRegion.OTHER,
            DiagnosticsRegionDetector.detect(
                localeCountry = "JP",
                timeZoneId = "Asia/Tokyo",
                ipCountry = "JP",
            ),
        )
    }

    @Test
    fun conflictingLocalSignalsWaitForIpCountry() {
        assertEquals(
            DiagnosticsRegion.UNKNOWN,
            DiagnosticsRegionDetector.detect(
                localeCountry = "US",
                timeZoneId = "Asia/Shanghai",
                ipCountry = null,
            ),
        )
    }

    @Test
    fun parsesCloudflareCountry() {
        assertEquals(
            "CN",
            DiagnosticsRegionDetector.parseCloudflareCountry("ip=203.0.113.1\nloc=cn\ntls=TLSv1.3\n"),
        )
    }
}
