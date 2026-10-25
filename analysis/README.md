# Working with a recording

`session_report.py` reads the CSV and JSON exported by BioSignal Bridge. It checks that the files describe the same session, validates the sample ranges and channel timing, then reports sample counts, amplitude summaries and observed sample intervals. It uses Python's standard library.

```sh
python3 analysis/session_report.py \
  --csv samples.csv \
  --metadata session.json \
  --output summary.json
```

Channel values use the scale stored in the metadata. Sample-rate estimates exclude intervals across marked gaps. Packet-loss counters are reported as recorded; they are not converted into an assumed number of missing samples. Empty channels return `null` amplitude and rate summaries.

These summaries describe the recording. They do not classify health conditions or provide diagnostic measurements.

The reader accepts up to one million sample rows, a 96 MiB CSV and 8 MiB of metadata. The metadata budget accommodates the app's 5,000 markers, including long Unicode labels. Files with mismatched sessions, undeclared channels, invalid raw values, backwards channel timestamps or inconsistent duration/count fields are rejected with a readable message.

`docs/fixtures/native-preview.csv` and its JSON companion are written by the Kotlin domain test using the public preview samples and the same exporter as the Android recording path. The Python tests read this pair to check that the two implementations agree on values, timing and packet counters. The fixture's start date is a fixed test value.

```sh
python3 -m unittest discover -s analysis -p 'test_*.py'
```
