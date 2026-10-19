# Developing the plugin

This page covers building, testing and packaging the plugin. It is not needed to
install or use it — see the [README](../README.md) for that.

## Requirements

- Python 3
- JDK 21
- Android SDK platform 36 and a current build-tools (36.0.0 in the release
  workflow), with accepted licences
- Linux or macOS

The included **Gradle 9.3.1** wrapper verifies its distribution checksum. Its
first run downloads Gradle and dependencies. These commands never install the
plugin on a kiosk.

## Test, build and check

```sh
python3 tools/test.py --fetch-test-deps
python3 tools/build.py --version 1.0.0
python3 tools/check_package.py dist/musicassistantplugin-1.0.0.zip
python3 tools/release_check.py
```

`--fetch-test-deps` verifies and downloads the pinned test-only org.json JAR used
by the pure-Java loopback API tests. For an offline build, supply it with
`--json-jar /path/to/org.json.jar`. Android fixtures also exercise real API
classes. Run builds and tests serially; do not run two Gradle processes in the
same checkout.

`tools/check.py` runs the source and manifest checks without Java; pass `--java`
and `--json-jar` to include the Java tests.

The Gradle APK is a build container only. The deliverable is the release ZIP;
never install the APK on a kiosk.

## SDK and vendored code

The compile-only SDK is included under `sdk/` with its upstream licence. Its exact
revision and Git blob hashes are verified against `sdk.lock.json`; SDK classes are
not shipped in the plugin DEX. `--fetch-sdk` can restore missing pinned SDK files
but refuses modified ones.

Vendored player provenance and modifications are documented in
[NOTICE.md](../NOTICE.md) and [DEPENDENCIES.md](../DEPENDENCIES.md).

## Releases

Release assets are produced and uploaded by **GitHub Actions**, using the
[official Kiosk template workflow](https://github.com/jxlarrea/kiosk-satellite-plugin-hello-world/blob/main/.github/workflows/build.yml)
adapted for the embedded player. The full release process, including manual
acceptance checks and community eligibility, is in
[RELEASING.md](RELEASING.md).
