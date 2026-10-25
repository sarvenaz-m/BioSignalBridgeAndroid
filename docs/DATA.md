# Public preview data

The preview contains information from the **MIT-BIH Arrhythmia Database**, made available under the [Open Data Commons Attribution License v1.0](https://physionet.org/content/mitdb/view-license/1.0.0/).

| Item | Included excerpt |
| --- | --- |
| Dataset version | 1.0.0 |
| Record and lead | 100, MLII |
| Interval | First 12 seconds |
| Original rate | 360 samples per second |
| Prepared rate | 250 samples per second |
| Sample count | 3,000 |
| ADC zero and gain | 1024; 200 counts per mV |
| Prepared values | Baseline-centred integer counts; multiply by 0.005 for mV |

Preparation decodes WFDB format 212, subtracts the ADC zero, linearly interpolates to 250 Hz and rounds to integer counts. No beat labels, diagnostic interpretations or derived vital signs are included. This resampled excerpt is used to demonstrate acquisition and review; it is not a clinical validation dataset for the application.

`data/provenance.json` records the original file URLs and hashes, preparation settings and output checksums. `data/preview_ecg.csv` keeps timestamps and raw values; `data/preview.json` is embedded in both interfaces. The preparation script reproduces these files from the pinned source.

To reproduce the excerpt, run `python3 data/prepare_preview.py --output-dir prepared-data`. The script downloads only the two versioned PhysioNet source files and verifies both checksums before decoding. If those files are already available locally, add `--source-dir path/to/source-files`. Use `--check data` to require an exact match with the bundled outputs. The script copies no header comments into the prepared files.

Packet-loss and reset scenarios use separate, constructed test packets. They do not alter the ECG excerpt or describe faults in the original recording.

## References

- Moody, G., and Mark, R. (2005). *MIT-BIH Arrhythmia Database*, version 1.0.0. PhysioNet. https://doi.org/10.13026/C2F305
- Moody, G. B., and Mark, R. G. (2001). The impact of the MIT-BIH Arrhythmia Database. *IEEE Engineering in Medicine and Biology Magazine*, 20(3), 45–50. https://doi.org/10.1109/51.932724
- Pollard, T., et al. (2026). PhysioNet as a global platform for biomedical research. *Nature Health*, 1, 792–795. https://doi.org/10.1038/s44360-026-00096-z
