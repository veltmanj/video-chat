# Changelog

All notable changes to this repository are documented in this file.

## [0.1.0] - 2026-03-20

### Added
- PulseRoom branding in the Angular frontend, including the `PULSEROOM` title and custom favicon.
- Lazy-loaded Angular routes to reduce the initial production bundle size.
- Camera-tile audio controls, microphone capture defaults, and remote audio handling improvements for live sessions.
- A profile-avatar context menu with logout support.
- GKE automation for bootstrap, teardown, recreate, frontend-only deploys, Grafana access, and spend reporting.
- Native GKE Gateway manifests and policy templates for public exposure on Google Kubernetes Engine.

### Changed
- Kubernetes and GKE automation scripts now support clearer step-by-step logging with `--verbose` and `--quiet` modes.
- GKE bootstrap now exposes cluster sizing controls such as machine type, node count, cluster location, and release channel.
- Default GKE sizing guidance was reduced toward leaner baseline capacity, with documentation describing cost impact and machine-type tradeoffs.

### Fixed
- Frontend production builds now avoid the previous bundle-budget warnings through route splitting and updated style budgets.
- Kubernetes workload templates include GKE-specific fixes for image pull behavior, persistent volume permissions, rollout strategy, and database storage layout.
