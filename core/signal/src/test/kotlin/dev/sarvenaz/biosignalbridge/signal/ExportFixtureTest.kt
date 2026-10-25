package dev.sarvenaz.biosignalbridge.signal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The checked recording pair is also read by the Python contract test. */
class ExportFixtureTest {
    @Test fun writesPortableNativePreviewRecordingForPython() {
        val root = File(requireNotNull(System.getProperty("biosignal.projectRoot")))
        val reference = File(root, "data/preview_ecg.csv").readLines().drop(1).filter { it.isNotBlank() }.map { row ->
            val columns = row.split(',')
            columns[0].trim().toLong() to columns[1].trim().toInt()
        }
        assertEquals(3000, reference.size)
        val origin = 5_000_000_000L
        val processor = SignalProcessor()
        val samples = reference.chunked(4).flatMapIndexed { index, values ->
            val packet = SensorPacket(index, values.first().first / 1000, 4000, 0, values.map { it.second })
            processor.accept(PacketDecoder.encode(packet), origin + values.last().first).samples
        }
        assertEquals(reference.map { it.first }, samples.map { it.timeNs - origin })
        assertEquals(reference.map { it.second }, samples.map { it.raw })
        assertFalse(samples.any { it.gapBefore })
        val session = Session(
            id = "native-preview-reference",
            startedAtEpochMs = 1_760_000_000_000L,
            originNs = origin,
            source = Source.PREVIEW,
            channels = listOf(Channel(0, "ECG · MLII", "mV", .005)),
            samples = samples,
            markers = listOf(Marker("review-midpoint", origin + 6_000_000_000L, "Review midpoint")),
            stats = processor.stats,
        )
        val destination = File(root, "docs/fixtures").apply { mkdirs() }
        File(destination, "native-preview.csv").writeText(CsvContract.encode(session), Charsets.UTF_8)
        File(destination, "native-preview.json").writeText(SessionJson.encode(session), Charsets.UTF_8)
        assertEquals(750L, processor.stats.acceptedPackets)
        assertTrue(File(destination, "native-preview.csv").length() > 0)
    }
}
