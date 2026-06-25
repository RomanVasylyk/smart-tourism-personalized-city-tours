# Pipeline data artifacts

Generated pipeline data is intentionally not tracked in git.

Keep only small, stable fixtures or samples in one of these locations:

- `pipeline/tests/fixtures/`
- `pipeline/data/sample/`
- `pipeline/data/fixtures/`

Raw OSM/provider downloads, processed POI files, transport graphs, reports, and
timetable PDFs/HTML files should be regenerated locally or stored outside git.
If a large artifact must be versioned for a release, store it with Git LFS and
document why it is required.
