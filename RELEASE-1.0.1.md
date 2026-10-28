# Music Assistant 1.0.1 (standalone fork)

Unofficial fork of 1.0.0 adding support for a standalone Music Assistant server.

- New **Standalone server URL** and **Standalone server token** settings under
  Connection. When both are set, the plugin talks to that server directly with
  the MA long-lived token instead of going through Home Assistant Ingress.
- Leave them blank to keep the original Home Assistant app behaviour.
