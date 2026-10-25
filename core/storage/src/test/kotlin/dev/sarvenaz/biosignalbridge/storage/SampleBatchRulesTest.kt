package dev.sarvenaz.biosignalbridge.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SampleBatchRulesTest {
    private fun sample(index: Long, time: Long = index * 2_000_000L, session: String = "session") =
        StoredSample(session, index, time, index * 2_000, 0, index.toInt(), 125)

    @Test fun acceptsContinuousBatchAcrossWrites() {
        SampleBatchRules.validate("session", listOf(sample(2), sample(3)), sample(1))
    }

    @Test fun rejectsMissingStoredIndex() {
        assertThrows(IllegalArgumentException::class.java) {
            SampleBatchRules.validate("session", listOf(sample(0), sample(2)), null)
        }
    }

    @Test fun rejectsTimeRegressionAcrossBatches() {
        assertThrows(IllegalArgumentException::class.java) {
            SampleBatchRules.validate("session", listOf(sample(2, 1_000)), sample(1))
        }
    }

    @Test fun allowsChannelsAtSameTimestamp() {
        SampleBatchRules.validate("session", listOf(sample(0, 0), sample(1, 0).copy(channel = 1)), null)
    }

    @Test fun rejectsCrossSessionMeasurement() {
        assertThrows(IllegalArgumentException::class.java) {
            SampleBatchRules.validate("session", listOf(sample(0, session = "other")), null)
        }
    }

    @Test fun rejectsNegativeQualityCounter() {
        assertThrows(IllegalArgumentException::class.java) { SessionMetrics(lostPackets = -1).requireValid() }
    }

    @Test fun retainsAdcUnitWithoutInventingCalibration() {
        val session = StoredSession("session", "Sensor recording", 1L, "live", 500.0)
        assertEquals("ADC counts", session.unit)
        assertEquals(1.0, session.scalePerCount, 0.0)
    }
}
