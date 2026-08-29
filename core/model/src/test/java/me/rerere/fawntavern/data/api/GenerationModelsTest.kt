package me.rerere.fawntavern.data.api

import org.junit.Assert.assertEquals
import org.junit.Test

class GenerationModelsTest {
    @Test
    fun samplingValuesUseTheSameTwoDecimalPrecisionAsTheEditor() {
        assertEquals(0.22, 0.22233355f.roundedSamplingDouble(), 0.0)
        assertEquals(-1.24, (-1.235f).roundedSamplingDouble(), 0.0)
    }
}
