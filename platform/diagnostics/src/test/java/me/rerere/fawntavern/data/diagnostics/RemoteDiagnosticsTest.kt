package me.rerere.fawntavern.data.diagnostics

import me.rerere.fawntavern.data.diagnostics.RemoteDiagnostics.Backend
import me.rerere.fawntavern.core.diagnostics.DiagnosticsRegion
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteDiagnosticsTest {
    @Test
    fun mainlandChinaUsesBugly() {
        assertEquals(
            Backend.BUGLY,
            RemoteDiagnostics.selectBackend(
                region = DiagnosticsRegion.MAINLAND_CHINA,
                buglyAvailable = true,
                firebaseAvailable = true,
            ),
        )
    }

    @Test
    fun mainlandChinaDoesNotFallBackToFirebase() {
        assertEquals(
            Backend.UNAVAILABLE,
            RemoteDiagnostics.selectBackend(
                region = DiagnosticsRegion.MAINLAND_CHINA,
                buglyAvailable = false,
                firebaseAvailable = true,
            ),
        )
    }

    @Test
    fun otherRegionsUseFirebase() {
        assertEquals(
            Backend.FIREBASE,
            RemoteDiagnostics.selectBackend(
                region = DiagnosticsRegion.OTHER,
                buglyAvailable = true,
                firebaseAvailable = true,
            ),
        )
    }

    @Test
    fun otherRegionsDoNotFallBackToBugly() {
        assertEquals(
            Backend.UNAVAILABLE,
            RemoteDiagnostics.selectBackend(
                region = DiagnosticsRegion.OTHER,
                buglyAvailable = true,
                firebaseAvailable = false,
            ),
        )
    }

    @Test
    fun unknownRegionDoesNotUpload() {
        assertEquals(
            Backend.UNAVAILABLE,
            RemoteDiagnostics.selectBackend(
                region = DiagnosticsRegion.UNKNOWN,
                buglyAvailable = true,
                firebaseAvailable = true,
            ),
        )
    }
}
