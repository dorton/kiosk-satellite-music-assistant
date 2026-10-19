# 1.0.0 release review

This is a release-readiness review, not an independent security audit or a claim
of universal Music Assistant/device compatibility.

## Review coverage

- Inventoried plugin Java, receiver Kotlin, SDK, retained player/shared modules,
  tests, Gradle modules, packaging, resource and licence files.
- Examined Ingress origin/session handling, bounded HTTP/artwork requests,
  redirect/cookie boundaries, encrypted receiver storage and registration.
- Reviewed single-shot mutation entry points, fresh identity/permission guards,
  queue/playlist/library removal checks, group-volume readback and EQ saves.
- Reviewed descriptions, setup/diagnostic wording, current installation guidance,
  SDK/host-class packaging boundaries and the official community checker rules.
- Scanned the publication tree for private endpoints, machine-specific paths,
  embedded private keys, temporary collectors and generated build output.

## Changes made

- Removed temporary TLS collector code, logging controls, volume-event observers
  and logging-only tests/tools. Retained functional regression tests and explicit
  connection diagnostics. Release R8 rules discard upstream development logging.
- Added concise setting descriptions, the requested radio lyrics text, corrected
  stale product/help wording and aligned reported versions with 1.0.0.
- Replaced development-history installation guidance with current setup,
  requirements, controls, privacy, limits and build/release documentation.
- Included the hash-pinned compile-only SDK and checksum-pinned Gradle wrapper;
  removed reliance on an external test init script and developer wrapper path.
- Added a release workflow adapted from the official template, pinned action
  revisions and local manifest/checksum/package consistency checks. Tag publication
  uses the Actions token; no deploy key or personal credential enters the source.

## Preserved behaviour

The installed ID, storage/key aliases, receiver identity, setting keys/defaults,
native-volume capability marker and account/server binding remain compatible.
No automatic install, uninstall, pairing reset or Kiosk preference change is added.

Group-volume target calculations and acceptance rules remain intact, including
native-step handling, stable readback and rejection of all-members-unchanged
failures. Removing trace collection does not relax any verification condition.
Queue remote-refresh, tap/hold menus, protected removal, staged EQ and deliberate
user-change protection are retained.

## Remaining release gates

- Run the complete source/Java/Android/JVM tests and package checks on the final
  source snapshot; examine the independent GitHub Actions result.
- Physical-device acceptance remains manual, particularly long hardware holds,
  standalone/option-off behaviour, call/speech restoration and UI rendering.
- Repository visibility, GitHub licence recognition, stable release asset uploader
  identity/digests and live directory eligibility must be verified after public
  publication. Do not equate local checks with community approval.

The repository owner controls visibility and submits the listing separately.
