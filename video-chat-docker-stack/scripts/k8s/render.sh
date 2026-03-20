#!/usr/bin/env bash
set -euo pipefail

# Render the full Kubernetes bundle into k8s/rendered so validate/apply/delete
# all operate on a deterministic manifest set.

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/common.sh"

require_command kubectl

usage() {
  cat <<'EOF'
Usage: ./scripts/k8s/render.sh [options]

Render the Kubernetes manifest bundle into k8s/rendered.

Options:
  --env <path>    Use a specific env file instead of k8s/k8s.env
  --verbose       Show narrated step-by-step output (default)
  --quiet         Reduce output to command errors and the final summary
  --help          Show this help text
EOF
}

ENV_FILE="${ENV_FILE_DEFAULT}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env)
      [[ $# -ge 2 ]] || fail "--env requires a path"
      ENV_FILE="$2"
      shift 2
      ;;
    --verbose)
      set_log_level verbose
      shift
      ;;
    --quiet)
      set_log_level quiet
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      fail "Unknown option: $1"
      ;;
  esac
done

load_k8s_env "${ENV_FILE}"
prepare_render_dir

template_enabled() {
  local template_name

  template_name="$(basename "$1")"
  case "${K8S_EXPOSURE_MODE}" in
    ingress-nginx)
      [[ "${template_name}" != "41-gke-gateway.yaml.tmpl" && "${template_name}" != "42-gke-policies.yaml.tmpl" ]]
      ;;
    gke-gateway)
      [[ "${template_name}" != "40-ingress.yaml.tmpl" ]]
      ;;
    *)
      fail "Unsupported K8S_EXPOSURE_MODE: ${K8S_EXPOSURE_MODE}"
      ;;
  esac
}

# Render template manifests first, then generate ConfigMaps from the monitoring
# assets so the final bundle is fully self-contained on disk.
log_step "Rendering template manifests"
while IFS= read -r template_path; do
  template_enabled "${template_path}" || continue
  render_template "${template_path}" "${RENDER_DIR}/$(basename "${template_path%.tmpl}")"
done < <(find "${K8S_DIR}/templates" -maxdepth 1 -type f -name '*.tmpl' | sort)

tmp_dir="$(mktemp -d)"
trap 'rm -rf "${tmp_dir}"' EXIT

render_template \
  "${K8S_DIR}/assets/promtail-config.yml.tmpl" \
  "${tmp_dir}/promtail-config.yml"
render_template \
  "${K8S_DIR}/assets/service-logs-k8s.json.tmpl" \
  "${tmp_dir}/service-logs-k8s.json"

cp "${ROOT_DIR}/monitoring/prometheus/prometheus.yml" "${tmp_dir}/prometheus.yml"
cp "${ROOT_DIR}/monitoring/prometheus/blackbox.yml" "${tmp_dir}/blackbox.yml"
cp "${ROOT_DIR}/monitoring/loki/loki-config.yml" "${tmp_dir}/loki-config.yml"
cp "${ROOT_DIR}/monitoring/grafana/provisioning/datasources/datasources.yml" "${tmp_dir}/datasources.yml"
cp "${ROOT_DIR}/monitoring/grafana/provisioning/dashboards/dashboards.yml" "${tmp_dir}/dashboards.yml"
cp "${ROOT_DIR}/monitoring/grafana/dashboards/application-signals.json" "${tmp_dir}/application-signals.json"
cp "${ROOT_DIR}/monitoring/grafana/dashboards/stack-overview.json" "${tmp_dir}/stack-overview.json"

# Generate the monitoring ConfigMaps via kubectl instead of hand-maintaining
# large YAML blobs in git.
log_step "Generating monitoring ConfigMaps"
generate_configmap_yaml \
  "${RENDER_DIR}/60-prometheus-configmap.yaml" \
  prometheus-config \
  --from-file=prometheus.yml="${tmp_dir}/prometheus.yml"

generate_configmap_yaml \
  "${RENDER_DIR}/61-blackbox-configmap.yaml" \
  blackbox-config \
  --from-file=blackbox.yml="${tmp_dir}/blackbox.yml"

generate_configmap_yaml \
  "${RENDER_DIR}/62-loki-configmap.yaml" \
  loki-config \
  --from-file=loki-config.yml="${tmp_dir}/loki-config.yml"

generate_configmap_yaml \
  "${RENDER_DIR}/63-promtail-configmap.yaml" \
  promtail-config \
  --from-file=promtail-config.yml="${tmp_dir}/promtail-config.yml"

generate_configmap_yaml \
  "${RENDER_DIR}/64-grafana-datasources-configmap.yaml" \
  grafana-datasources \
  --from-file=datasources.yml="${tmp_dir}/datasources.yml"

generate_configmap_yaml \
  "${RENDER_DIR}/65-grafana-dashboards-provider-configmap.yaml" \
  grafana-dashboards-provider \
  --from-file=dashboards.yml="${tmp_dir}/dashboards.yml"

generate_configmap_yaml \
  "${RENDER_DIR}/66-grafana-dashboards-configmap.yaml" \
  grafana-dashboards \
  --from-file=application-signals.json="${tmp_dir}/application-signals.json" \
  --from-file=stack-overview.json="${tmp_dir}/stack-overview.json" \
  --from-file=service-logs.json="${tmp_dir}/service-logs-k8s.json"

printf 'Rendered Kubernetes bundle to %s\n' "${RENDER_DIR}"
