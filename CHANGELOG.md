# Changelog

All notable changes to this repository are documented in this file.

## [0.2.0] - 2026-03-25

### Added
- Passwordless email registration and sign-in across the Angular frontend, backoffice API, and local Docker stack.
- Stored social-profile avatars with upload, removal, authenticated delivery, and frontend avatar management in the social hub.
- Standalone Angular login panels and social-hub column components to break the frontend into clearer UI slices.
- Kubernetes and GKE teardown safeguards that select the intended kube context automatically for local and cloud cleanup flows.

### Changed
- GKE bootstrap defaults now point at the leaner `europe-west4` baseline instead of the previous `us-central1` defaults.
- GKE teardown documentation now states more clearly which resources are preserved by default and which flags remove the cluster or Artifact Registry repository.

### Fixed
- Local Kubernetes teardown no longer reports success when `kubectl` is disconnected or pointed at the wrong cluster.
- GKE teardown wrappers now pass the active cluster context through to the shared Kubernetes teardown script instead of falling back to a local context.

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
