# Music Assistant 1.0.2 (standalone fork)

- Fix: library reads with a standalone server failed with "HA session changed".
  The standalone connection now reuses one session while the URL and token
  are unchanged.
