# Player dependencies

The plugin uses the retained MIT SendspinDroid player/shared source. See
`assets/sendspindroid-LICENSE` and `assets/sendspindroid-UPSTREAM.md` in the ZIP.
The latter documents the encrypted pre-rc1 compatibility pin used with
aiosendspin 9.1.1; it is not a claim of compatibility with every Sendspin server.

Runtime dependencies resolved by the pinned Gradle modules:

- Kotlin 2.4.0 — Apache-2.0, JetBrains.
- kotlinx.coroutines 1.10.2 — Apache-2.0, JetBrains.
- kotlinx.serialization 1.8.0 — Apache-2.0, JetBrains.
- Ktor 3.1.1 — Apache-2.0, JetBrains.
- OkHttp / Okio (via `io.ktor:ktor-client-okhttp:3.1.1`) — Apache-2.0, Square.
- Bouncy Castle bcprov-jdk18on 1.80 — MIT, the Legion of the Bouncy Castle.
- SLF4J (via Ktor 3.1.1) — MIT, QOS.ch.

The Kiosk plugin SDK is compile-only (Apache-2.0), supplied by the host and
excluded from the delivered DEX. Kiosk application implementation code is not
included. The Gradle APK is a build container only; it is not a deliverable and
must never be installed on the kiosk.

Build/test-only tools include the Apache-2.0 Gradle 9.3.1 wrapper, JUnit 4.13.2
(EPL-1.0), Robolectric 4.16.1 (MIT), and org.json 20240303 (public domain).
The org.json test JAR and Gradle distribution are checksum-verified. These
tools and test fixtures are not packaged in the plugin DEX. GitHub Actions
dependencies are pinned to reviewed upstream commit identities in the workflow.
