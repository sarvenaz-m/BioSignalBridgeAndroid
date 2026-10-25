#!/usr/bin/env python3
"""Reproduce the public 12-second preview from the pinned PhysioNet record."""

from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
from pathlib import Path
import tempfile
from urllib.request import Request, urlopen

SOURCE_URL = "https://physionet.org/files/mitdb/1.0.0/"
SOURCES = {
    "100.dat": "b2ea3c250e56e48f4b7b90697832b8ecd1afa1e0bb31f2dcfea4ed6e1075a639",
    "100.hea": "60ebc904c7bf3e04d142638d3fd5c903e8dc9f10f1ea3264e07926aa089ee75e",
}
MAX_SOURCE_BYTES = 3_000_000
SOURCE_HZ = 360
OUTPUT_HZ = 250
DURATION_SECONDS = 12
OUTPUT_COUNT = OUTPUT_HZ * DURATION_SECONDS
ADC_ZERO = 1024
GAIN_COUNTS_PER_MV = 200


def sha256(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def read_source(name: str, directory: Path | None) -> bytes:
    if directory is None:
        request = Request(SOURCE_URL + name, headers={"User-Agent": "BioSignalBridge-preview/1.0.0"})
        with urlopen(request, timeout=45) as response:
            content = response.read(MAX_SOURCE_BYTES + 1)
    else:
        path = directory / name
        if path.stat().st_size > MAX_SOURCE_BYTES:
            raise ValueError(f"{name} exceeds the source file size limit.")
        content = path.read_bytes()
    if len(content) > MAX_SOURCE_BYTES:
        raise ValueError(f"{name} exceeds the source file size limit.")
    actual = sha256(content)
    if actual != SOURCES[name]:
        raise ValueError(f"{name} checksum differs from the pinned source: {actual}")
    return content


def decode_first_lead(data: bytes, header: bytes) -> list[int]:
    # Comments are deliberately neither retained nor copied to derived files.
    lines = [line.split() for line in header.decode("ascii").splitlines() if line.strip() and not line.lstrip().startswith("#")]
    if len(lines) != 3 or lines[0][:4] != ["100", "2", "360", "650000"]:
        raise ValueError("The record header does not match the pinned two-channel recording.")
    signal = lines[1]
    if signal[0:5] != ["100.dat", "212", "200", "11", "1024"] or signal[-1] != "MLII":
        raise ValueError("The first channel does not match the expected format, gain and ADC zero.")
    # WFDB 212 stores one signed 12-bit value from each channel in three bytes.
    needed = ((OUTPUT_COUNT - 1) * SOURCE_HZ + OUTPUT_HZ - 1) // OUTPUT_HZ + 1
    if len(data) < needed * 3 or len(data) % 3:
        raise ValueError("The sample file is incomplete.")
    samples = []
    for index in range(needed):
        low, packed = data[index * 3], data[index * 3 + 1]
        value = low | ((packed & 15) << 8)
        if value >= 2048:
            value -= 4096
        if value == -2048:
            raise ValueError("The selected preview interval contains a missing-value code.")
        samples.append(value - ADC_ZERO)
    return samples


def prepare(samples: list[int]) -> dict[str, bytes]:
    values = []
    for index in range(OUTPUT_COUNT):
        left, fraction = divmod(index * SOURCE_HZ, OUTPUT_HZ)
        right = min(left + 1, len(samples) - 1)
        # Rational indexing avoids accumulating a floating-point sample position.
        value = (samples[left] * (OUTPUT_HZ - fraction) + samples[right] * fraction) / OUTPUT_HZ
        values.append(round(value))
    preview = {
        "recordId": "100", "sampleRateHz": OUTPUT_HZ, "unit": "mV", "scalePerCount": 1 / GAIN_COUNTS_PER_MV,
        "samples": values, "sourceName": "MIT-BIH Arrhythmia Database",
        "sourceUrl": "https://physionet.org/content/mitdb/1.0.0/", "license": "ODC-By-1.0",
        "durationSeconds": DURATION_SECONDS, "lead": "MLII", "sourceSampleRateHz": SOURCE_HZ,
        "preparation": "First 12 seconds; format-212 decode, subtract ADC zero 1024, linear interpolation from 360 Hz to 250 Hz, round to integer counts. No beat labels or clinical measurements are inferred.",
    }
    preview_bytes = (json.dumps(preview, separators=(",", ":"), ensure_ascii=False) + "\n").encode("utf-8")
    csv_stream = io.StringIO(newline="")
    writer = csv.writer(csv_stream, lineterminator="\r\n")
    writer.writerow(["timestamp_ns", "raw_value"])
    writer.writerows((index * 1_000_000_000 // OUTPUT_HZ, value) for index, value in enumerate(values))
    csv_bytes = csv_stream.getvalue().encode("utf-8")
    provenance = {
        "dataset": "MIT-BIH Arrhythmia Database", "version": "1.0.0", "doi": "10.13026/C2F305",
        "record": "100", "channel": "MLII", "sourceRateHz": SOURCE_HZ, "sampleRateHz": OUTPUT_HZ,
        "durationSeconds": DURATION_SECONDS, "count": OUTPUT_COUNT, "gainCountsPerMv": GAIN_COUNTS_PER_MV,
        "adcZero": ADC_ZERO, "license": "Open Data Commons Attribution License v1.0",
        "sourceFiles": [{"name": name, "url": SOURCE_URL + name, "sha256": checksum} for name, checksum in SOURCES.items()],
        "outputs": [{"name": "preview.json", "sha256": sha256(preview_bytes)}, {"name": "preview_ecg.csv", "sha256": sha256(csv_bytes)}],
    }
    return {"preview.json": preview_bytes, "preview_ecg.csv": csv_bytes, "provenance.json": (json.dumps(provenance, indent=2) + "\n").encode("utf-8")}


def main() -> int:
    parser = argparse.ArgumentParser(description="Prepare the public ECG preview from checksum-pinned PhysioNet files.")
    parser.add_argument("--source-dir", type=Path, help="Directory containing 100.dat and 100.hea; omit to download the official files")
    parser.add_argument("--output-dir", type=Path, default=Path(__file__).resolve().parent, help="Destination for the three prepared files")
    parser.add_argument("--check", type=Path, help="Compare generated bytes with this directory before writing")
    args = parser.parse_args()
    try:
        data = read_source("100.dat", args.source_dir)
        header = read_source("100.hea", args.source_dir)
        outputs = prepare(decode_first_lead(data, header))
        if args.check:
            different = [name for name, content in outputs.items() if not (args.check / name).is_file() or (args.check / name).read_bytes() != content]
            if different:
                raise ValueError("Generated files differ: " + ", ".join(different))
        args.output_dir.mkdir(parents=True, exist_ok=True)
        for name, content in outputs.items():
            with tempfile.NamedTemporaryFile(dir=args.output_dir, prefix=name + ".", delete=False) as stream:
                temporary = Path(stream.name)
                stream.write(content)
            temporary.replace(args.output_dir / name)
            print(f"{name}  {len(content)} bytes  {sha256(content)}")
    except (OSError, UnicodeError, ValueError) as error:
        parser.exit(2, f"Cannot prepare preview: {error}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
