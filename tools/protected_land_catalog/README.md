# Protected-land source catalog

`refresh_catalog.py` validates the shared protected-land source catalog and
performs a minimal ArcGIS count query against each boundary service. It does
not download the national boundary datasets.

The explicit Android and Apple release gates invoke it automatically. A check
attempt is cached in the ignored root `.release-state/` directory for seven days, even
when a source is temporarily unavailable. Repeated release or debug builds do
not retry during that interval. A failed automatic attempt retains the
checked-in last-known-good catalog and prints a warning.

Force a new check when a source is known to have changed:

```sh
python3 tools/protected_land_catalog/refresh_catalog.py --force
```

The Android equivalent through its release gate is:

```sh
./gradlew :app:releaseCheck -PforceProtectedLandCatalogRefresh=true
```

Apple accepts `--force-land-catalog-refresh` in both `release-check.sh` and
`archive-for-testflight.sh`.

Use `--strict` in a connected maintenance job when every source must pass.
The ordinary release workflow deliberately warns rather than blocking an
urgent field release because an upstream GIS service is temporarily offline.
