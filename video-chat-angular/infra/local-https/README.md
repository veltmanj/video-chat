# Local HTTPS + WSS setup (LAN / iPad)

This setup gives you:

- `https://<LAN_IP>:8443` for the Angular frontend
- `wss://<LAN_IP>:8443/rsocket` proxied to the broker (`9898`)
- Optional `https://<LAN_IP>:8443/backoffice-api/...` proxied to backoffice (`7901`)

## 1) Prerequisites

Assume `${REPO_ROOT}` is the directory containing the sibling repositories.

- Docker Desktop running
- `mkcert` installed:
  - `brew install mkcert nss`

## 2) Generate TLS certificate for your LAN IP

Find your LAN IP, then run:

```bash
cd "${REPO_ROOT}/video-chat-angular/infra/local-https"
./scripts/generate-cert.sh <LAN_IP>
```

This writes:

- `certs/videochat-lan.pem`
- `certs/videochat-lan-key.pem`

## 3) Start app services

In separate terminals:

```bash
# Angular
cd "${REPO_ROOT}/video-chat-angular"
npm start
```

```bash
# Broker
cd "${REPO_ROOT}/video-chat-rsocket-broker"
mvn spring-boot:run
```

```bash
# Backoffice
cd "${REPO_ROOT}/video-chat-backoffice"
mvn spring-boot:run
```

## 4) Start HTTPS reverse proxy

```bash
cd "${REPO_ROOT}/video-chat-angular/infra/local-https"
./scripts/start-proxy.sh
```

Open:

- `https://<LAN_IP>:8443`

## 5) Trust certificate on iPad

iPad Safari needs the local CA trusted.

On Mac, get the mkcert CA root path:

```bash
mkcert -CAROOT
```

Take `rootCA.pem` from that folder and install it on the iPad (AirDrop/mail/files), then:

1. Install the profile on iPad
2. Go to `Settings > General > About > Certificate Trust Settings`
3. Enable full trust for that root certificate

Then reopen `https://<LAN_IP>:8443` on iPad and camera access should work.

## 6) Stop proxy

```bash
cd "${REPO_ROOT}/video-chat-angular/infra/local-https"
./scripts/stop-proxy.sh
```
