# Upstream references

Version 0.9.0-dev adds MIT SendspinDroid player/shared modules; their exact
revisions and compatibility changes are recorded in
`third_party/sendspindroid/UPSTREAM.md`. The plugin-owned receiver adapter and
secure store are adapted from our retained standalone app code, not Kiosk code.
See `DEPENDENCIES.md` and the ZIP's dependency notice/license assets.

This independently written plugin uses the public Kiosk Satellite SDK 1.
The compile-time SDK belongs to its upstream authors and is Apache-2.0 licensed:
https://github.com/jxlarrea/kiosk-satellite-plugin-hello-world

`sdk.lock.json` pins the exact upstream commit and Git blob identities. Build
tools verify the included `sdk/` files and can restore missing files with
`--fetch-sdk`. The upstream LICENSE is retained in `sdk/LICENSE`. SDK classes are not packaged
in the plugin: Kiosk supplies them at runtime.

The Music Assistant HTTP command names follow its public API and the existing
card's read-only calls. No Music Assistant implementation code is bundled.
https://github.com/music-assistant/server

The included Gradle 9.3.1 wrapper comes from https://github.com/gradle/gradle
and is Apache-2.0 licensed (see `sdk/LICENSE` for the licence text). Its JAR
and distribution checksums are checked during release validation.
The release workflow is adapted from the Apache-2.0 Kiosk SDK template.
Release R8 rules remove upstream Android/shared development log calls; upstream
source and required notices are retained. Temporary plugin logging is removed.

The plugin never uses internal Kiosk classes, reflection, or browser storage.
