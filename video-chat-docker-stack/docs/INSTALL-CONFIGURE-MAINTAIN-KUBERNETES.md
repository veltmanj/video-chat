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
- Ingress-based HTTPS routing

The Kubernetes bundle is intentionally more Kubernetes-native than the Docker Compose stack:

- Caddy is replaced by a standard Kubernetes Ingress.
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

Cluster prerequisites:

- A reachable Kubernetes cluster
- An Ingress controller, such as ingress-nginx
- A default or known storage class for persistent volumes
- Image pull access to the registry that will hold the frontend, broker, and backoffice images

Sibling repositories required for image builds:

- `../video-chat-angular`
- `../video-chat-rsocket-broker`
- `../video-chat-backoffice`

## 3. Initial setup

Copy the Kubernetes environment template:

```bash
cd "${REPO_ROOT}/video-chat-docker-stack"
cp k8s/k8s.env.example k8s/k8s.env
```

Edit `k8s/k8s.env` and set at least:

- `K8S_HOST`: ingress hostname presented to browsers and WebSocket clients
- `K8S_INGRESS_CLASS_NAME`: ingress class used by your cluster
- `K8S_TLS_SECRET_NAME`: TLS secret name referenced by the Ingress objects
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

- For quick local or non-production setup, use `./scripts/k8s/create-self-signed-tls.sh`.
- For real environments, create `K8S_TLS_SECRET_NAME` with your own certificate flow before deployment.

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
- `K8S_HOST`: hostname routed by the Ingress resources
- `K8S_INGRESS_CLASS_NAME`: ingress class name, for example `nginx`
- `K8S_TLS_SECRET_NAME`: secret referenced by all Ingress resources

### 7.2 Images

- `K8S_IMAGE_REGISTRY`: registry prefix used to compute the three app image names
- `K8S_IMAGE_TAG`: tag shared by frontend, broker, and backoffice
- `K8S_PUSH_IMAGES`: when `true`, `build-images.sh` pushes after each build

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
