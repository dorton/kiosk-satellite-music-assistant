# Vendored SendspinDroid modules

Source: https://github.com/chrisuthe/SendspinDroid
Revision: e4417143e8211a286e367cfee10c11cdd6c33b10 (MIT; see LICENSE).

Only player/shared components and LICENSE are included. No upstream application
UI, settings implementation or Kiosk application code is copied.

Dev.4 compatibility pin: shared/src and SendSpinProtocolHandler originate at
**50266109420b17b8de6a79b364dd8d3602dd21a4**, immediately before the rc1 wire
change. They implement the encrypted pre-rc1 protocol used by aiosendspin 9.1.1.
The Android engine, decoders and module build scripts retain the original newer
revision above. SyncErrorFilter also retains that revision for the audio engine.
The unused rc1 InboundConnections component is omitted (no listening/discovery).

SendSpin.kt is this app's version-specific host adapter, not an unchanged upstream
file: explicit outbound connection, no automatic retry, no incoming listener,
strict encrypted-only transport, token-only pairing, immutable unpaired-access
denial, server-bound record validation, re-handshake support and ordered shutdown.
ProtocolHandler uses an injected output-delay persistence method instead of
upstream UserSettings. Its older plaintext-capable base is not exposed by the
host; all application frames must be encrypted and a legacy server is rejected.

Shared upstream tests are retained from the protocol pin. Added offline JVM/real
Python aiosendspin 9.1.1 interop tests and host plaintext-rejection test.

Dev.6 host compatibility patches: client/state uses static_delay_ms and only
set_static_delay in its state-level supported_commands, per aiosendspin 9.1.1
models (hello retains volume/mute). Parser handles set_static_delay. Generated
states and the actual encrypted host state/command path are tested against the
real Python model. Earlier upstream comments using output_delay reflect a
different spec revision, not the target server's accepted schema.

Host compatibility patch: player/build.gradle.kts explicitly applies the Kotlin
Android plugin because Flutter's generated Android project disables AGP built-in
Kotlin.

Security patch: SendSpinProtocolHandler suppresses raw protocol payload logging
(including parse-failure exception details) in this host's debug builds. Protocol
behaviour is unchanged; private pairing material must not leak through logs.

Host requirements: Android API 26+, SDK 36, JDK 21. The shared JVM/Android code
does not provide an iOS audio implementation. Dependency licences remain applicable.
# Plugin 0.9.1 adapter change

`SendSpin.connectedServerId` exposes the completed transport handshake's server
identity read-only. Version 0.9.2 keeps it bound to the same receiver connection,
separately from the authenticated MA `/info` instance UUID. The exact receiver
protocol-player ID on that MA instance is checked before any pairing flow.
The 0.9.1 comparison between those two server-ID namespaces was incorrect.
