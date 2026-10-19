# Security

This plugin runs inside Kiosk Satellite with the permissions declared in its
manifest. Its receiver is independent of Kiosk's built-in player.

- The `overlay` capability provides the touch interface.
- `ha` provides Home Assistant/Ingress access through the host account.
- `host.read` reads kiosk identity, volume and voice/call state.
- `host.control` supports explicit volume control and temporary playback-related
  behaviour. It does not authorise silent changes to saved Kiosk preferences.

Ingress sessions are kept in memory. Receiver credentials use an encrypted,
server-bound store. Certificate verification, encrypted transport, account
permissions and server identity checks must not be disabled to fix a connection.
Local playback, group volume, library writes and EQ have separate fresh checks.
Uncertain write outcomes require inspection, not automatic resubmission.

No public security audit is claimed. Review dependencies and grant only the
permissions appropriate for the account/device. Network access to Home Assistant,
Music Assistant and configured artwork providers is required for those features.

## Reporting a vulnerability

Use GitHub's **Report a vulnerability** option if enabled for this repository.
Otherwise open an issue requesting a private reporting channel, without exploit
details, credentials or personal information. Do not post tokens, pairing data,
private URLs, raw logs or Home Assistant configuration in public issues.
