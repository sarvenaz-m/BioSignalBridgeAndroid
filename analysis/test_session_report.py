import copy
import csv
import json
from pathlib import Path
import tempfile
import unittest

from session_report import HEADER, MAX_METADATA_BYTES, RecordingError, read_recording, summarize


class RecordingTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.metadata = {
            "schema": "biosignal-bridge.session/v1", "sessionId": "test-1", "source": "PREVIEW",
            "startedAtEpochMs": 1000, "timeUnit": "ns", "originNs": 0, "sampleCount": 4, "durationNs": 50_000_000,
            "channels": [{"id": 0, "name": "Wave", "unit": "mV", "scalePerCount": .5}],
            "markers": [{"id": "m1", "timeNs": 10_000_000, "label": "Tap left"}],
            "stats": {"receivedPackets": 2, "acceptedPackets": 2, "malformedPackets": 0, "lostPackets": 3, "duplicatePackets": 0, "outOfOrderPackets": 0, "clockResets": 0},
        }
        self.rows = [["test-1", 0, 1, 0, 0, -2, "false"], ["test-1", 0, 1, 10_000_000, 10_000, 2, "false"], ["test-1", 0, 5, 40_000_000, 40_000, 4, "true"], ["test-1", 0, 5, 50_000_000, 50_000, 0, "false"]]

    def recording(self, metadata=None, rows=None):
        (self.root / "session.json").write_text(json.dumps(self.metadata if metadata is None else metadata), encoding="utf-8")
        with (self.root / "samples.csv").open("w", newline="", encoding="utf-8") as stream:
            writer = csv.writer(stream)
            writer.writerow(HEADER)
            writer.writerows(self.rows if rows is None else rows)
        return read_recording(self.root / "samples.csv", self.root / "session.json")

    def test_scales_values_and_excludes_gap_from_rate(self):
        report = summarize(self.recording())
        channel = report["channels"][0]
        self.assertEqual(4, report["sample_count"])
        self.assertEqual("PREVIEW", report["source"])
        self.assertEqual(100, channel["estimated_rate_hz"])
        self.assertEqual(100, channel["observed_rate_hz"])
        self.assertEqual(0, channel["interval_jitter_us"])
        self.assertEqual(0, channel["adc_rail_samples"])
        self.assertAlmostEqual(1.25 ** .5, channel["population_stddev"])
        self.assertEqual(-1, channel["minimum"])
        self.assertEqual(2, channel["maximum"])
        self.assertEqual(.5, channel["mean"])
        self.assertEqual(2, channel["segment_count"])
        self.assertEqual(3, report["packet_counters"]["lostPackets"])

    def test_count_and_duration_must_match_companion_metadata(self):
        for key in ("sampleCount", "durationNs"):
            metadata = copy.deepcopy(self.metadata)
            metadata[key] += 1
            with self.assertRaises(RecordingError):
                self.recording(metadata=metadata)

    def test_undeclared_channel_is_rejected(self):
        rows = copy.deepcopy(self.rows)
        rows[1][1] = 1
        with self.assertRaisesRegex(RecordingError, "undeclared"):
            self.recording(rows=rows)

    def test_decreasing_channel_time_is_rejected(self):
        rows = copy.deepcopy(self.rows)
        rows[2][3] = 5
        with self.assertRaisesRegex(RecordingError, "backwards"):
            self.recording(rows=rows)

    def test_sequence_and_tick_wrap_are_valid_unsigned_values(self):
        rows = copy.deepcopy(self.rows)
        rows[0][2], rows[0][4] = 65535, 2**32 - 1
        rows[1][2], rows[1][4] = 0, 0
        self.assertEqual(4, len(self.recording(rows=rows).samples))

    def test_malformed_boolean_is_rejected(self):
        rows = copy.deepcopy(self.rows)
        rows[0][-1] = "False"
        with self.assertRaisesRegex(RecordingError, "gap flag"):
            self.recording(rows=rows)

    def test_other_session_cannot_be_mixed_in(self):
        rows = copy.deepcopy(self.rows)
        rows[0][0] = "other"
        with self.assertRaisesRegex(RecordingError, "another session"):
            self.recording(rows=rows)

    def test_nonfinite_scale_and_boolean_time_are_rejected(self):
        metadata = copy.deepcopy(self.metadata)
        metadata["channels"][0]["scalePerCount"] = float("nan")
        with self.assertRaises(RecordingError):
            self.recording(metadata=metadata)
        metadata = copy.deepcopy(self.metadata)
        metadata["durationNs"] = True
        with self.assertRaises(RecordingError):
            self.recording(metadata=metadata)

    def test_duplicate_marker_identifiers_are_rejected(self):
        metadata = copy.deepcopy(self.metadata)
        metadata["markers"] *= 2
        with self.assertRaisesRegex(RecordingError, "unique"):
            self.recording(metadata=metadata)

    def test_empty_recording_is_valid_without_invented_statistics(self):
        metadata = copy.deepcopy(self.metadata)
        metadata.update(sampleCount=0, durationNs=0, markers=[])
        report = summarize(self.recording(metadata=metadata, rows=[]))
        self.assertIsNone(report["channels"][0]["mean"])
        self.assertIsNone(report["channels"][0]["estimated_rate_hz"])
        self.assertEqual(0, report["channels"][0]["segment_count"])

    def test_simultaneous_channels_use_their_own_time_order(self):
        metadata = copy.deepcopy(self.metadata)
        metadata["channels"].append({"id": 1, "name": "Second", "unit": "a.u.", "scalePerCount": 1})
        rows = copy.deepcopy(self.rows)
        rows[1][1], rows[1][3] = 1, 0
        rows[3][1] = 1
        self.assertEqual(2, len(summarize(self.recording(metadata=metadata, rows=rows))["channels"]))

    def test_duplicate_timestamps_do_not_create_infinite_rate(self):
        rows = copy.deepcopy(self.rows)
        rows[1][3] = 0
        channel = summarize(self.recording(rows=rows))["channels"][0]
        self.assertEqual(1, channel["duplicate_timestamps"])
        self.assertEqual(100, channel["estimated_rate_hz"])
        self.assertEqual(100, channel["observed_rate_hz"])
        self.assertEqual(0, channel["interval_jitter_us"])
        self.assertEqual(0, channel["adc_rail_samples"])
        self.assertAlmostEqual(1.25 ** .5, channel["population_stddev"])

    def test_json_counters_must_be_numbers_and_oversized_csv_integers_are_rejected(self):
        metadata = copy.deepcopy(self.metadata)
        metadata["stats"]["receivedPackets"] = "2"
        with self.assertRaisesRegex(RecordingError, "JSON integer"):
            self.recording(metadata=metadata)
        rows = copy.deepcopy(self.rows)
        rows[0][3] = "9" * 5000
        with self.assertRaises(RecordingError):
            self.recording(rows=rows)

    def test_native_marker_capacity_with_long_unicode_labels_fits_metadata_budget(self):
        metadata = copy.deepcopy(self.metadata)
        metadata["markers"] = [{"id": f"marker-{index}", "timeNs": index, "label": "訊" * 240} for index in range(5000)]
        recording = self.recording(metadata=metadata)
        metadata_bytes = (self.root / "session.json").stat().st_size
        self.assertGreater(metadata_bytes, 1024 * 1024)
        self.assertLess(metadata_bytes, MAX_METADATA_BYTES)
        self.assertEqual(5000, len(recording.metadata["markers"]))

    def test_metadata_cap_and_exact_csv_header_remain_enforced(self):
        self.recording()
        (self.root / "session.json").write_bytes(b" " * (MAX_METADATA_BYTES + 1))
        with self.assertRaisesRegex(RecordingError, "8 MiB"):
            read_recording(self.root / "samples.csv", self.root / "session.json")
        self.recording()
        (self.root / "samples.csv").write_text("timestamp_ns,raw_value\n0,1\n", encoding="utf-8")
        with self.assertRaisesRegex(RecordingError, "columns"):
            read_recording(self.root / "samples.csv", self.root / "session.json")

    def test_kotlin_preview_export_matches_public_sample_data(self):
        root = Path(__file__).resolve().parents[1]
        fixture_csv, fixture_json = root / "docs/fixtures/native-preview.csv", root / "docs/fixtures/native-preview.json"
        if not fixture_csv.exists() or not fixture_json.exists():
            self.skipTest("The native fixture is produced by :core:signal:test.")
        recording = read_recording(fixture_csv, fixture_json)
        with (root / "data/preview_ecg.csv").open(encoding="utf-8", newline="") as stream:
            reference = list(csv.DictReader(stream))
        self.assertEqual([int(row["raw_value"]) for row in reference], [sample.raw_value for sample in recording.samples])
        self.assertEqual([int(row["timestamp_ns"]) for row in reference], [sample.timestamp_ns for sample in recording.samples])
        report = summarize(recording)
        self.assertEqual("PREVIEW", report["source"])
        self.assertEqual(3000, report["sample_count"])
        self.assertEqual(750, report["packet_counters"]["acceptedPackets"])
        self.assertEqual(250, report["channels"][0]["estimated_rate_hz"])
        self.assertEqual(0, report["packet_counters"]["lostPackets"])


if __name__ == "__main__":
    unittest.main()
