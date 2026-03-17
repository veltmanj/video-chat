#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/common.sh"

require_command kubectl

load_k8s_env "${1:-${ENV_FILE_DEFAULT}}"
prepare_render_dir

while IFS= read -r template_path; do
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
