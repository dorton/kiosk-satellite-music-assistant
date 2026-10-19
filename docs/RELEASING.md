# Release and community listing

## Source layout

- `src/`: plugin controls, policies and native UI.
- `receiver/`: encrypted local Sendspin receiver and host integration.
- `sdk/`: hash-pinned, compile-only public Kiosk SDK and Apache-2.0 licence.
- `third_party/sendspindroid/`: retained MIT player/shared source and provenance.
- `tests/` and `player-build/bundle/src/test/`: regression fixtures.
- `tools/`, `player-build/`: checks, packaging and self-contained Gradle build.
- `assets/`, documentation, licences and notices: release resources and guidance.

Do not commit generated packages, APKs, DEX, build caches, local SDK paths,
credentials, private capture data or historical development ZIPs. Historical
development release notes are ignored and retained only in the local workspace.

## Publish a stable release

1. Review changes, dependencies, licence notices and user-facing text. Keep the
   installed ID `musicassistantplugin`, receiver identity and setting keys stable.
2. Update the manifest, setup version, receiver software-version suffix and
   build-only versionCode/versionName together. Preserve the receiver's
   `Music Assistant Plugin` prefix and native-volume capability advertisement.
3. Run `python3 tools/test.py --fetch-test-deps`, build and run both package/release checks. Test
   the candidate manually on a kiosk before recommending broad installation.
4. Commit and push the reviewed source, then push a matching `v<version>` tag.
   The **Release** workflow tests and builds first, then creates a stable GitHub
   Release using `RELEASE-<version>.md` and uploads:
   - `kiosk-satellite-plugin.json`
   - `musicassistantplugin-<version>.zip`
   - `musicassistantplugin-<version>.zip.sha256`
5. Inspect the Actions result and downloaded assets. The standalone manifest must
   match the one inside the ZIP; the checksum must name that exact ZIP. Assets
   must be uploaded by `github-actions[bot]`, not manually.

Alternatively, publishing a stable GitHub Release manually triggers the official
template-style upload path. Do not combine both paths for the same version.

Do not replace a published version with different bytes. Use a new version for a
correction. The workflow deliberately refuses to overwrite existing assets;
inspect a partial upload before deciding how to recover. A manual workflow run
must target the existing release tag, not the default branch. Git deploy-key
access is sufficient to push the tag; Actions uses its own repository-scoped
token to publish the release. No personal API token is committed or needed.

## Manual acceptance

- Upgrade in place without resetting pairing, settings or custom receiver names.
- Browse/search and return through detail, Library and selected-player views.
- Play an album, track and radio station; check fallback artwork and radio text.
- Skip remotely with Queue open; verify the current item updates. Tap to play;
  hold/overflow for actions; check current/played-item removal protection.
- Create/save playlists and confirm destructive library operations.
- Check individual/group sliders, hardware press/hold/reversal/zero behaviour,
  standalone and option-off behaviour on each target device.
- Check speech/call pause/duck/resume and restoration without overriding a
  deliberate user change. Check Close does not stop music or alter preferences.
- Verify EQ remains Apply-only and respects permissions/unrelated filters.
- Verify Remote Admin refresh/check is read-only and stale results are rejected.

## Community eligibility

The official requirements are maintained by the
[Kiosk SDK repository](https://github.com/jxlarrea/kiosk-satellite-plugin-hello-world#submit-your-plugin).
Local validation is necessary but cannot establish public listing eligibility.

- Public GitHub repository containing one plugin, all source and a licence GitHub
  recognises. Only the repository owner changes its visibility.
- Stable, non-draft release with the three correctly named Actions-uploaded assets.
- Valid SDK 1 manifest, icon, package contents, size and matching checksum.
- README at the release tag, owner submission and an available plugin ID.

After publishing publicly, the owner submits through the official repository's
**Submit a plugin** issue form. Check the eligibility report before approval.
The plugin is not listed until that separate process succeeds. Do not submit,
change repository visibility or claim approval as part of an ordinary release.
