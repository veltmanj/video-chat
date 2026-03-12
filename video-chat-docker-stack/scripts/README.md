## Script Layout

The `scripts/` directory now keeps the user-facing entrypoints at the top level and groups the real implementations by concern underneath:

- `stack/`: start, stop, validate, cleanup, and logs
- `monitoring/`: health checks and monitoring smoke tests
- `social-db/`: backup, restore, reload, and dev seeding
- `setup/`: local trust and OAuth setup helpers
- `security/`: live auth/security drills
- `smoke/`: higher-level application smoke tests

Existing commands like `./scripts/up.sh` and `./scripts/check.sh` still work. Those top-level files are thin compatibility wrappers so docs, habits, and container mounts do not break while the folder stays easier to scan.
