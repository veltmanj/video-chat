## Script Layout

The `scripts/` directory now keeps the user-facing entrypoints at the top level and groups the real implementations by concern underneath:

- `stack/`: start, stop, validate, cleanup, and logs
- `k8s/`: bootstrap, teardown, render, validate, apply, delete, TLS, image build, Docker Desktop exposure, and database operations for Kubernetes
- `gke/`: Google Cloud and GKE bootstrap, teardown, targeted rollout automation, private access helpers, and cost reporting layered on top of the Kubernetes bundle
- `monitoring/`: health checks and monitoring smoke tests
- `social-db/`: backup, restore, reload, and dev seeding
- `setup/`: local trust and OAuth setup helpers
- `security/`: live auth/security drills
- `smoke/`: higher-level application smoke tests

Existing commands like `./scripts/up.sh` and `./scripts/check.sh` still work. Those top-level files are thin compatibility wrappers so docs, habits, and container mounts do not break while the folder stays easier to scan.

## Maintenance Conventions

The grouped scripts follow a few rules that make the shell automation easier to maintain:

- `scripts/k8s/common.sh` is the shared helper layer. It owns env loading, template rendering, image naming, and basic shell utilities. Scripts that need those behaviors should source it instead of re-implementing them.
- `k8s/k8s.env` is the single source of truth for deploy-time configuration. Bootstrap scripts may seed or normalize it, but downstream scripts should read from it rather than duplicating defaults.
- `k8s/rendered/` is treated as disposable output. `render.sh` recreates it from scratch so `validate.sh`, `apply.sh`, and `delete.sh` all act on the same manifest bundle.
- Top-level scripts are compatibility wrappers only. The real implementation lives in the grouped subdirectory with the same responsibility.
- GKE scripts layer on top of the Kubernetes bundle. They provision cloud resources first, then call the Kubernetes scripts for image build, render, validate, apply, or teardown.
- Standalone entrypoint scripts support `-h` and `--help` to print a short description and their accepted arguments.

## Typical Entrypoints

The most relevant scripts for day-to-day operations are:

- `./scripts/stack/up.sh`: start the local Docker Compose stack
- `./scripts/k8s/bootstrap.sh`: deploy the stack to a generic Kubernetes cluster
- `./scripts/gke/bootstrap.sh`: provision GCP/GKE prerequisites and deploy the stack
- `./scripts/gke/recreate.sh`: replace the GKE cluster while preserving the reusable edge resources
- `./scripts/gke/deploy-frontend.sh`: rebuild and roll out only the frontend image
- `./scripts/gke/check-certificate.sh`: inspect or wait for the Google-managed certificate status
- `./scripts/gke/check-public-endpoint.sh`: inspect or wait for real public HTTPS reachability
- `./scripts/gke/recreate-certificate.sh`: recreate the managed certificate resource in place
- `./scripts/gke/access-grafana.sh`: open a private local port-forward to Grafana
- `./scripts/gke/show-expenses.sh`: report actual GCP spend from the billing export
- `./scripts/gke/teardown.sh`: remove the GKE deployment and optional cloud resources

## Readability Notes

If you need to change behavior, start here:

- env defaults or image naming: `scripts/k8s/common.sh`
- Kubernetes manifest generation: `scripts/k8s/render.sh`
- Kubernetes lifecycle orchestration: `scripts/k8s/bootstrap.sh` and `scripts/k8s/teardown.sh`
- Google Cloud provisioning flow: `scripts/gke/bootstrap.sh` and `scripts/gke/teardown.sh`
- cluster replacement flow while preserving edge resources: `scripts/gke/recreate.sh`
- targeted production frontend rollouts: `scripts/gke/deploy-frontend.sh`
