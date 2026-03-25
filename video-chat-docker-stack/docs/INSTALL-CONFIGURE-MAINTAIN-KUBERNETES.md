# Kubernetes Install, Configure, and Maintain Guide

## 1. Purpose

This guide describes the Kubernetes deployment bundle for the video chat stack. It deploys:

- Angular frontend
- Spring Boot RSocket broker
- Spring Boot backoffice
- PostgreSQL social database
- MinIO object storage
- Prometheus and blackbox-exporter
- Loki and Promtail
- Grafana
- Ingress- or Gateway-based HTTPS routing

The Kubernetes bundle is intentionally more Kubernetes-native than the Docker Compose stack:

- Caddy is replaced by a standard Kubernetes Ingress or, on GKE, a managed Gateway.
- Runtime secrets come from Kubernetes `Secret` resources instead of Compose-only file handoff.
- The broker can load JWKS directly from configured provider URLs, so Vault is not required for Kubernetes.

## 2. Prerequisites

Assume `${REPO_ROOT}` is the directory containing the sibling repositories.

Required tools:

```bash
kubectl version --client
docker version
openssl version
```

Optional for GKE automation:

```bash
gcloud version
```

Cluster prerequisites:

- A reachable Kubernetes cluster
- Either an Ingress controller, such as ingress-nginx, or GKE Gateway API support
- A default or known storage class for persistent volumes
- Image pull access to the registry that will hold the frontend, broker, and backoffice images

Sibling repositories required for image builds:

- `../video-chat-angular`
- `../video-chat-rsocket-broker`
- `../video-chat-backoffice`

## 3. Initial setup

Choose one of these paths:

- Generic Kubernetes: keep `K8S_EXPOSURE_MODE=ingress-nginx` and use the existing `scripts/k8s/*` flow.
- GKE public deployment: set `K8S_EXPOSURE_MODE=gke-gateway` and use `./scripts/gke/bootstrap.sh`.
- Frontend-only GKE rollout: use `./scripts/gke/deploy-frontend.sh` when only the Angular app changed.

### 3.1 GKE fast path

Copy the template and set the public inputs:

```bash
cd "${REPO_ROOT}/video-chat-docker-stack"
cp k8s/k8s.env.example k8s/k8s.env
```

Set at least:

- `K8S_HOST`: public hostname for end users, for example `videochat.example.com`
- `GOOGLE_OAUTH_CLIENT_ID`: Google OAuth web client ID that allows `https://<K8S_HOST>`
- `GCP_PROJECT_ID`: Google Cloud project that owns the GKE cluster
- `GCP_PROJECT_NAME`: display name to use if the project must be created
- `GCP_BILLING_ACCOUNT`: recommended when the project must be created and used for billable resources
- `GCP_REGION`: Artifact Registry region, for example `europe-west4`
- `GKE_CLUSTER_LOCATION`: GKE zone or region, for example `europe-west4-a`
- `GCP_DNS_MANAGED_ZONE`: optional Cloud DNS managed zone for automated A-record updates

Then run:

```bash
./scripts/gke/bootstrap.sh
```

If you want tighter control over cost and capacity, you can override the cluster
shape directly when bootstrapping. Example:
```bash
./scripts/gke/bootstrap.sh --machine-type e2-standard-2 --node-count 1
```

The bootstrap script persists those values back into `k8s/k8s.env`, so later
runs reuse the same cluster shape unless you change it again.

### 3.2 GKE sizing guide

The biggest recurring cost levers in this stack are:

- `GKE_MACHINE_TYPE`: how much CPU and memory each node has
- `GKE_NODE_COUNT`: how many nodes run all the time
- persistent storage: the PostgreSQL, MinIO, Prometheus, Loki, and Grafana PVC sizes
- public edge resources: the GKE Gateway/load balancer, static IP, and certificate

For this stack, `e2-standard-2` is a good lower-cost default:

- `e2-standard-2`: 2 vCPUs and 8 GB RAM per node
- `e2-standard-4`: 4 vCPUs and 16 GB RAM per node

That means `e2-standard-4` is roughly a double-sized compute node compared with
`e2-standard-2`, and at the same node count it should be expected to cost
materially more. Node count multiplies that again, so:

- `1 x e2-standard-2`: leanest baseline for this stack
- `2 x e2-standard-2`: more headroom and better resilience, but roughly doubles node spend
- `2 x e2-standard-4`: much more expensive baseline and usually unnecessary unless real load proves it

Common E2 options and what they mean:

- `e2-standard-*`: balanced general-purpose nodes, about 4 GB RAM per vCPU
- `e2-highmem-*`: more memory per vCPU, useful if Prometheus, Loki, or databases are memory constrained
- `e2-highcpu-*`: less memory per vCPU, useful for CPU-heavy workloads that do not need much RAM
- `e2-micro`, `e2-small`, `e2-medium`: smaller shared-core shapes, cheaper but usually too constrained for this full stack once monitoring and storage services are included

Practical recommendation:

- start with `--machine-type e2-standard-2 --node-count 1`
- move to `2` nodes if you see resource pressure or want better availability during node maintenance
- only move to `e2-standard-4` or a `highmem` family after observing actual CPU or memory pressure in Grafana/`kubectl top`

Important limitation of the bootstrap automation:

- bootstrap can resize node count on an existing cluster
- bootstrap does not change the machine type of an existing node pool in place
- if you want to move from `e2-standard-4` to `e2-standard-2` on an existing cluster, recreate the cluster or replace the node pool

For later frontend-only production updates, use:

```bash
./scripts/gke/deploy-frontend.sh
```

Useful flags:

- `--tag <value>`: deploy a specific frontend image tag
- `--runmode dev`: switch the frontend runtime mode to `development`
- `--skip-build`: reuse an already-pushed frontend image
- `--timeout <value>`: override the rollout timeout

By default, `deploy-frontend.sh` forces `VIDEOCHAT_APP_MODE=production`, even if
`k8s/k8s.env` currently contains a different value.

### Private Grafana Access

Grafana is intentionally not exposed on the public GKE Gateway. The safest way
to access it is with a local Kubernetes port-forward:

```bash
./scripts/gke/access-grafana.sh
```

That script:

- reads the Grafana admin password from the `app-secrets` Kubernetes secret
- prints the local URL and credentials
- starts `kubectl port-forward svc/grafana 3000:3000`

Optional flag:

- `--port <value>`: use a different local port if `3000` is already in use

### Spend Reporting

To report actual spend for the configured GCP project, use:

```bash
./scripts/gke/show-expenses.sh
```

This script queries a Cloud Billing export in BigQuery and, by default, reports
costs from the GCP project creation time until now.

One-time setup:

1. Open the official Google guide for Cloud Billing export to BigQuery:
   https://docs.cloud.google.com/billing/docs/how-to/export-data-bigquery-setup
2. In the Google Cloud console, choose a project to hold the billing dataset.
   It can be `pulseroom-videochat`, but Google recommends a separate FinOps or billing-admin project. The dataset project must be linked to the same billing account.
3. In BigQuery, create a dataset for billing data.
   Suggested dataset name: `billing_export`
4. In `Billing` -> `Billing export` -> `BigQuery export`, enable at least:
   - `Standard usage cost data`
   Optional:
   - `Detailed usage cost data`
5. Choose the dataset from step 3 and save.
6. In BigQuery, note the resulting table name.
   Typical names:
   - `gcp_billing_export_v1_<billing_account_id>`
   - `gcp_billing_export_resource_v1_<billing_account_id>`
7. Add the export location to `k8s/k8s.env`.

Required values in `k8s/k8s.env`:

- `GCP_BILLING_EXPORT_PROJECT`
- `GCP_BILLING_EXPORT_DATASET`
- `GCP_BILLING_EXPORT_TABLE`

Example:

```bash
GCP_BILLING_EXPORT_PROJECT=pulseroom-videochat
GCP_BILLING_EXPORT_DATASET=billing_export
GCP_BILLING_EXPORT_TABLE=gcp_billing_export_v1_01082F_532492_1620B4
```

If those values are blank, the script attempts to discover a standard or
detailed billing export table automatically across the projects your active
account can read.

Notes:

- `show-expenses.sh` reports actual spend from the billing export, not the heuristic cost rating printed by `bootstrap.sh`.
- Standard usage cost export is sufficient for `show-expenses.sh`.
- Detailed usage cost export is useful if you later want resource-level analysis, including GKE cost allocation.
- BigQuery storage and queries for billing export incur small additional charges.
- New billing rows are not instant. Expect some lag before fresh usage appears in BigQuery.

What bootstrap does:

- forces `K8S_EXPOSURE_MODE=gke-gateway`
- creates the configured project if it does not exist yet
- optionally links the project to `GCP_BILLING_ACCOUNT`
- enables the required Google Cloud APIs
- creates or reuses the Artifact Registry repository
- creates or updates a Standard GKE cluster with Gateway API enabled
- reserves a global static IP and creates a Google-managed SSL certificate
- updates the Cloud DNS A record when `GCP_DNS_MANAGED_ZONE` is set
- builds and pushes the frontend, broker, and backoffice images
- applies the Kubernetes stack through the existing render and deploy flow

The public routes remain:

- `https://<K8S_HOST>/`
- `wss://<K8S_HOST>/rsocket`
- `https://<K8S_HOST>/social-api/social/v1/...`
- `https://<K8S_HOST>/backoffice-api/api/...`

Notes:

- The bootstrap script generates strong values for `SOCIAL_DB_PASSWORD`, `MINIO_ROOT_PASSWORD`, and `GRAFANA_ADMIN_PASSWORD` when they are blank.
- The Google-managed certificate may stay in `PROVISIONING` until the DNS A record for `K8S_HOST` points at the reserved static IP.
- This stack currently uses a Promtail `DaemonSet` with `hostPath` mounts, so a Standard GKE cluster is the intended target, not Autopilot.

To inspect the managed certificate after bootstrap:

```bash
./scripts/gke/check-certificate.sh
```

That script prints:

- the overall managed certificate status
- the per-domain status for `K8S_HOST`
- the reserved static IP
- the currently resolved public IPv4 addresses for `K8S_HOST`

If you want it to wait until the certificate is ready:

```bash
./scripts/gke/check-certificate.sh --wait
```

Useful flags:

- `--timeout <seconds>`
- `--interval <seconds>`
- `--env <path>`

To check whether the public endpoint is actually reachable for end users:

```bash
./scripts/gke/check-public-endpoint.sh
```

To wait until HTTPS is genuinely serving:

```bash
./scripts/gke/check-public-endpoint.sh --wait
```

That script combines:

- managed certificate state
- DNS-to-static-IP matching
- HTTP redirect behavior
- HTTPS reachability

If the managed certificate appears stuck and you want to recreate just that
resource without rebuilding the cluster:

```bash
./scripts/gke/recreate-certificate.sh --yes
```

Because Google Cloud does not allow deletion of a certificate resource while it
is attached to the live HTTPS proxy, that script rotates to a newly created
certificate name, patches the Gateway to use it, updates `k8s/k8s.env`, and
then removes the old certificate once it is no longer in use.

Useful recreate-certificate flags:

- `--wait`
- `--timeout <seconds>`
- `--interval <seconds>`
- `--env <path>`
- `--name <value>`

To remove the deployed namespace plus the public GKE-side resources again while
keeping the cluster and Artifact Registry repository:

```bash
./scripts/gke/teardown.sh
```

Useful GKE teardown flags:

- `--delete-dns-record`: remove the public `A` record from `GCP_DNS_MANAGED_ZONE`
- `--delete-cluster`: delete the configured GKE cluster
- `--delete-artifact-repository`: delete the configured Artifact Registry repository
- `--delete-env-file`: remove `k8s/k8s.env`
- `--delete-rendered`: remove `k8s/rendered/`

If you need to replace the cluster shape itself, for example to move from
`e2-standard-4` to `e2-standard-2`, use:

```bash
./scripts/gke/recreate.sh --yes
```

Useful recreate flags:

- `--machine-type <type>`
- `--node-count <count>`
- `--cluster-location <id>`
- `--release-channel <id>`
- `--skip-build`
- `--skip-dns-update`

How `recreate.sh` differs from `teardown.sh`:

- `recreate.sh` is for replacing the cluster and then redeploying the stack
- `teardown.sh` is for removing the deployment and optionally decommissioning supporting resources
- `recreate.sh` preserves the static IP, managed certificate, DNS record, Artifact Registry repository, and local env file
- `teardown.sh` removes the managed certificate and static IP by default, and can also delete the cluster, Artifact Registry repository, DNS record, env file, and rendered manifests
- `recreate.sh` requires `--yes` because it is intentionally destructive to the running cluster but is not meant to fully decommission the public entrypoint

### 3.2 Generic Kubernetes path

Copy the Kubernetes environment template:

```bash
cd "${REPO_ROOT}/video-chat-docker-stack"
cp k8s/k8s.env.example k8s/k8s.env
```

Edit `k8s/k8s.env` and set at least:

- `K8S_HOST`: ingress hostname presented to browsers and WebSocket clients
- `K8S_INGRESS_CLASS_NAME`: ingress class used by your cluster when `K8S_EXPOSURE_MODE=ingress-nginx`
- `K8S_TLS_SECRET_NAME`: TLS secret name referenced by the Ingress objects when `K8S_EXPOSURE_MODE=ingress-nginx`
- `K8S_IMAGE_REGISTRY`: target registry prefix, for example `ghcr.io/your-org`
- `K8S_IMAGE_TAG`: deployment tag, for example `2026-03-17`
- `GOOGLE_OAUTH_CLIENT_ID`: Google OAuth web client ID
- `SOCIAL_DB_PASSWORD`, `MINIO_ROOT_PASSWORD`, `GRAFANA_ADMIN_PASSWORD`: strong runtime secrets
- `BROKER_JWT_GOOGLE_AUDIENCE`: normally the same value as `GOOGLE_OAUTH_CLIENT_ID`
- `BACKOFFICE_SOCIAL_GOOGLE_AUDIENCE`: normally the same value as `GOOGLE_OAUTH_CLIENT_ID`

Storage settings:

- Set `K8S_*_STORAGE_CLASS` to a storage class that exists in your cluster.
- Set `K8S_*_STORAGE_SIZE` based on expected usage.

TLS options:

- For `ingress-nginx`, use `./scripts/k8s/create-self-signed-tls.sh` for quick local or non-production setup.
- For `ingress-nginx` in real environments, create `K8S_TLS_SECRET_NAME` with your own certificate flow before deployment.
- For `gke-gateway`, the GKE bootstrap script uses a Google-managed SSL certificate instead of a Kubernetes TLS secret.

Fast rebuild path:

```bash
./scripts/k8s/bootstrap.sh
```

What it does:

- creates `k8s/k8s.env` from `k8s/k8s.env.example` if needed
- seeds Kubernetes values from the existing Docker stack `.env`
- installs ingress-nginx automatically when the `nginx` ingress class is missing
- builds images, validates manifests, recreates the self-signed TLS secret, and deploys the stack
- on Docker Desktop, exposes ingress on `:8080` and `:8443` if host `80` and `443` are already in use

To force a clean rebuild of the namespace:

```bash
./scripts/k8s/bootstrap.sh --recreate-namespace
```

To tear the environment down again:

```bash
./scripts/k8s/teardown.sh
```

That removes the application namespace, resets Docker Desktop ingress exposure when this stack changed it, and removes `ingress-nginx` only when bootstrap installed it. To force removal of `ingress-nginx` as well:

```bash
./scripts/k8s/teardown.sh --delete-ingress-nginx
```

`teardown.sh` defaults to the `docker-desktop` kube context for the local Kubernetes flow. Set `VIDEOCHAT_KUBECTL_CONTEXT=<name>` if you want to target a different local context. It does not remove Kubernetes system workloads such as pods in `kube-system`, and it leaves any shared namespaces unrelated to this stack alone. The GKE teardown flow fetches credentials for the configured cluster and passes that kube context through automatically.

## 4. Build and publish images

Build the three application images:

```bash
./scripts/k8s/build-images.sh
```

To push them automatically, set:

```bash
K8S_PUSH_IMAGES=true
```

If `K8S_IMAGE_REGISTRY` is blank, the scripts build local-only image names. That is only useful when your cluster can already see local Docker images.

## 5. Validate and deploy

Validate rendered manifests:

```bash
./scripts/k8s/validate.sh
```

Create a quick TLS secret if you do not already have one:

```bash
./scripts/k8s/create-self-signed-tls.sh
```

Deploy the stack:

```bash
./scripts/k8s/apply.sh
```

Wait explicitly for rollouts:

```bash
./scripts/k8s/wait.sh
```

The full end-to-end rebuild flow is also available through:

```bash
./scripts/k8s/bootstrap.sh
```

For Docker Desktop local clusters, if ports `80` and `443` are already occupied on the host, expose ingress on alternate localhost ports:

```bash
./scripts/k8s/expose-docker-desktop.sh
```

That patches `ingress-nginx-controller` to publish on `8080` and `8443`, which makes the app reachable at:

```bash
https://<K8S_HOST>:8443/
```

Rendered manifests are written to:

```bash
k8s/rendered/
```

## 6. Runtime access

Application endpoints:

- App: `https://<K8S_HOST>/`
- Broker RSocket websocket: `wss://<K8S_HOST>/rsocket`
- Shared social API: `https://<K8S_HOST>/social-api/social/v1/...`

Docker Desktop note:

- If you used `./scripts/k8s/expose-docker-desktop.sh`, use `https://<K8S_HOST>:8443/` instead of the default port `443`.
- Use the HTTPS URL directly. The HTTP redirect from `:8080` may redirect to `https://<K8S_HOST>/` without preserving `:8443`.

Monitoring access by port-forward:

```bash
kubectl -n "${K8S_NAMESPACE}" port-forward svc/grafana 3000:3000
kubectl -n "${K8S_NAMESPACE}" port-forward svc/prometheus 9090:9090
kubectl -n "${K8S_NAMESPACE}" port-forward svc/minio 9001:9001
```

Then open:

- Grafana: `http://127.0.0.1:3000`
- Prometheus: `http://127.0.0.1:9090`
- MinIO console: `http://127.0.0.1:9001`

## 7. Configuration reference

### 7.1 Core cluster settings

- `K8S_NAMESPACE`: namespace created and managed by the deployment scripts
- `K8S_HOST`: hostname routed by the Ingress or Gateway resources
- `K8S_EXPOSURE_MODE`: `ingress-nginx` or `gke-gateway`
- `K8S_INGRESS_CLASS_NAME`: ingress class name, for example `nginx`, used by `ingress-nginx`
- `K8S_TLS_SECRET_NAME`: TLS secret referenced by `ingress-nginx`
- `K8S_GKE_GATEWAY_NAME`: Gateway resource name used by `gke-gateway`
- `K8S_GKE_GATEWAY_CLASS`: GKE GatewayClass, usually `gke-l7-global-external-managed`
- `K8S_GKE_GATEWAY_ADDRESS_NAME`: reserved global static IP name used by the Gateway
- `K8S_GKE_GATEWAY_SSL_CERTIFICATE_NAME`: Google-managed SSL certificate resource name
- `K8S_GKE_GATEWAY_BACKEND_TIMEOUT_SEC`: backend timeout applied to broker and backoffice through `GCPBackendPolicy`

### 7.2 Images

- `K8S_IMAGE_REGISTRY`: registry prefix used to compute the three app image names
- `K8S_IMAGE_TAG`: tag shared by frontend, broker, and backoffice
- `K8S_PUSH_IMAGES`: when `true`, `build-images.sh` pushes after each build

GKE bootstrap-specific inputs:

- `GCP_PROJECT_ID`
- `GCP_PROJECT_NAME`
- `GCP_BILLING_ACCOUNT`
- `GCP_PROJECT_ORGANIZATION_ID`
- `GCP_PROJECT_FOLDER_ID`
- `GCP_REGION`
- `GCP_DNS_MANAGED_ZONE`
- `GKE_CLUSTER_NAME`
- `GKE_CLUSTER_LOCATION`
- `GKE_MACHINE_TYPE`
- `GKE_NODE_COUNT`
- `GKE_RELEASE_CHANNEL`
- `GKE_ARTIFACT_REPOSITORY`

These values can also be overridden directly on the `gke/bootstrap.sh`
command line with:

- `--cluster-location`
- `--machine-type`
- `--node-count`
- `--release-channel`

Computed images:

- `${K8S_IMAGE_REGISTRY}/video-chat-angular:${K8S_IMAGE_TAG}`
- `${K8S_IMAGE_REGISTRY}/video-chat-rsocket-broker:${K8S_IMAGE_TAG}`
- `${K8S_IMAGE_REGISTRY}/video-chat-backoffice:${K8S_IMAGE_TAG}`

### 7.3 Storage

- `K8S_SOCIAL_DB_STORAGE_CLASS`, `K8S_SOCIAL_DB_STORAGE_SIZE`
- `K8S_MINIO_STORAGE_CLASS`, `K8S_MINIO_STORAGE_SIZE`
- `K8S_PROMETHEUS_STORAGE_CLASS`, `K8S_PROMETHEUS_STORAGE_SIZE`
- `K8S_LOKI_STORAGE_CLASS`, `K8S_LOKI_STORAGE_SIZE`
- `K8S_GRAFANA_STORAGE_CLASS`, `K8S_GRAFANA_STORAGE_SIZE`

### 7.4 Frontend

- `GOOGLE_OAUTH_CLIENT_ID`
- `VIDEOCHAT_APP_MODE`
- `VIDEOCHAT_AI_AGENT_ENABLED`
- `VIDEOCHAT_AI_AGENT_ENDPOINT`
- `VIDEOCHAT_AI_AGENT_NAME`
- `VIDEOCHAT_AI_AGENT_MENTION`

### 7.5 Broker JWT providers

- `BROKER_JWT_ENABLED`
- `BROKER_JWT_CACHE_TTL`
- `BROKER_JWT_CLOCK_SKEW`
- `BROKER_JWT_GOOGLE_ENABLED`
- `BROKER_JWT_GOOGLE_AUDIENCE`
- `BROKER_JWT_GOOGLE_JWK_SET_URI`
- `BROKER_JWT_APPLE_ENABLED`
- `BROKER_JWT_APPLE_JWK_SET_URI`
- `BROKER_JWT_X_ENABLED`
- `BROKER_JWT_X_JWK_SET_URI`

For Google login, the normal configuration is:

- `BROKER_JWT_GOOGLE_ENABLED=true`
- `BROKER_JWT_GOOGLE_AUDIENCE=<GOOGLE_OAUTH_CLIENT_ID>`
- `BROKER_JWT_GOOGLE_JWK_SET_URI=https://www.googleapis.com/oauth2/v3/certs`

### 7.6 Backoffice and social media

- `BACKOFFICE_AI_ENABLED`
- `BACKOFFICE_AI_API_KEY`
- `BACKOFFICE_AI_MODEL`
- `BACKOFFICE_AI_ASSISTANT_NAME`
- `BACKOFFICE_SOCIAL_GOOGLE_AUDIENCE`
- `BACKOFFICE_SOCIAL_GOOGLE_JWK_SET_URI`
- `BACKOFFICE_SOCIAL_MEDIA_ENABLED`
- `BACKOFFICE_SOCIAL_MEDIA_BUCKET`
- `BACKOFFICE_SOCIAL_MEDIA_MAX_UPLOAD_BYTES`
- `BACKOFFICE_SOCIAL_MEDIA_MAX_FILES_PER_POST`

### 7.7 Secrets

The Kubernetes bundle writes these into the `app-secrets` secret:

- `SOCIAL_DB_PASSWORD`
- `MINIO_ROOT_PASSWORD`
- `GRAFANA_ADMIN_PASSWORD`
- `BACKOFFICE_AI_API_KEY`

For production, move those values into your preferred secret management workflow and adapt the secret manifest generation if needed.

## 8. Day-2 operations

Redeploy after image or config changes:

```bash
./scripts/k8s/apply.sh
```

Delete the full namespace:

```bash
./scripts/k8s/delete.sh
```

Run the full teardown companion:

```bash
./scripts/k8s/teardown.sh
```

For GKE-managed public resources, use the companion instead:

```bash
./scripts/gke/teardown.sh
```

Useful teardown flags:

- `--delete-ingress-nginx`: remove `ingress-nginx` even if it was not marked as bootstrap-managed
- `--delete-env-file`: remove `k8s/k8s.env`
- `--delete-rendered`: remove `k8s/rendered/`

Back up the social database:

```bash
./scripts/k8s/backup-social-db.sh
```

Restore the social database from a dump:

```bash
./scripts/k8s/restore-social-db.sh ./backups/social-db-videochat-<timestamp>.dump
```

Inspect pods:

```bash
kubectl -n "${K8S_NAMESPACE}" get pods
kubectl -n "${K8S_NAMESPACE}" get ingress
kubectl -n "${K8S_NAMESPACE}" get pvc
```

Inspect logs:

```bash
kubectl -n "${K8S_NAMESPACE}" logs deploy/frontend
kubectl -n "${K8S_NAMESPACE}" logs deploy/broker
kubectl -n "${K8S_NAMESPACE}" logs deploy/backoffice
```

## 9. Troubleshooting

### WebSocket works in Safari but fails in Chrome

Chrome strictly enforces RFC 6455 for WebSocket connections, while Safari is more lenient. If `wss://<K8S_HOST>/rsocket` works in Safari but silently fails in Chrome, there are three things to check:

#### 1. TLS certificate not trusted

Chrome rejects WebSocket connections to servers with untrusted certificates, even when the page itself loaded without warnings.

The self-signed TLS script (`scripts/k8s/create-self-signed-tls.sh`) generates a persistent local CA and signs server certificates with it. On macOS it also trusts the CA in the login keychain. If you regenerated certificates or are on a fresh machine, run:

```bash
./scripts/k8s/create-self-signed-tls.sh
```

Verify the CA is trusted:

```bash
security find-certificate -c "videochat-local-ca" ~/Library/Keychains/login.keychain-db
```

#### 2. HTTP/2 interfering with WebSocket upgrades

When nginx-ingress advertises HTTP/2, Chrome uses the HTTP/2 Extended CONNECT mechanism for WebSocket. nginx-ingress does not support proxying Extended CONNECT to upstream HTTP/1.1 WebSocket servers.

The bootstrap script disables HTTP/2 in the nginx-ingress ConfigMap automatically. If you installed ingress-nginx manually, apply the setting yourself:

```bash
kubectl -n ingress-nginx patch configmap ingress-nginx-controller \
  --type merge -p '{"data":{"use-http2":"false"}}'
kubectl -n ingress-nginx rollout restart deployment ingress-nginx-controller
```

On the broker side, the Netty HTTP/2 cleartext upgrade handler can also intercept WebSocket upgrade requests. The broker deployment template sets `SERVER_HTTP2_ENABLED=false` to prevent this.

#### 3. WebSocket subprotocol not confirmed (most common cause)

The Angular client opens the WebSocket with `Sec-WebSocket-Protocol: rsocket`. Per RFC 6455 §4.2.2, when a client requests a subprotocol the server must confirm it in the response. Chrome fails the connection if the server does not confirm; Safari ignores the omission.

The broker's `application.yml` includes `spring.rsocket.server.spec.protocols: rsocket` which tells Spring to confirm the subprotocol. If you override broker configuration, make sure this property is preserved:

```yaml
spring:
  rsocket:
    server:
      transport: websocket
      mapping-path: /rsocket
      spec:
        protocols: rsocket
```

Note: this issue does not manifest in the Docker Compose stack because Caddy's reverse proxy passes the `Sec-WebSocket-Protocol` header through to the client, effectively masking the missing server confirmation.

## 10. Notes and tradeoffs

- This deployment bundle is intentionally single-instance for the stateful services.
- Grafana, Loki, Prometheus, MinIO, and PostgreSQL use single PVC-backed workloads.
- Promtail assumes a standard Kubernetes node log layout under `/var/log/pods`.
- Backoffice admin-only routes from the Compose Caddy setup are not exposed by default in Kubernetes. The ingress bundle exposes the app, broker websocket path, and shared social API path.
- The Kubernetes path removes the Vault dependency by configuring broker JWT providers with direct JWKS URLs. The Docker Compose stack still supports Vault-backed JWKS loading unchanged.
