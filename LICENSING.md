# Licensing Notes

Status as of March 9, 2026: this repository now applies the `Apache-2.0` license at the repository root.

## Selected license

Selected license: `Apache-2.0`.

Why this fits this codebase:

- It is permissive, so the frontend, broker, stack, and supporting docs can be used, modified, and redistributed in commercial and internal environments.
- It includes an explicit patent grant and patent termination clause, which is usually a better default than `MIT` for a mixed frontend/backend infrastructure repository.
- It supports a `NOTICE` file if you later need to carry forward attribution or bundled notices across the monorepo.

## Alternatives considered

- `MIT`: simpler and shorter, but it does not include the same explicit patent grant language as `Apache-2.0`.
- `AGPL-3.0-or-later`: suitable only if you want a strong network copyleft model for hosted modifications.
- Proprietary/internal terms: suitable if this repository is not intended to become open source at all.

## Practical follow-up

1. Confirm that the copyright owner named in future headers, notices, and releases is consistent across the repository.
2. Add SPDX identifiers to source files only if the project later decides that file-level tagging is worth the maintenance cost.
3. Add a root `NOTICE` file if future redistribution requires bundled attribution notices.
4. Mirror the chosen SPDX identifier in package metadata, Maven metadata, and release documentation where appropriate.

## Recommendation boundary

This is a practical engineering recommendation, not legal advice. If the repository will be published outside a private team, the final license choice should be reviewed by the copyright holder and, if needed, legal counsel.
