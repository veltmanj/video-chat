#!/bin/sh
set -eu

usage() {
  cat <<'EOF'
Usage: ./scripts/social-db/seed-dev-social-db.sh

Seed the dev social database inside the container environment when
SOCIAL_DB_SEED_ENABLED=true. Existing profiles are left intact unless
SOCIAL_DB_SEED_FORCE=true.

Arguments:
  -h, --help    Show this help text.
EOF
}

if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then
  usage
  exit 0
fi

if [ "$#" -ne 0 ]; then
  usage >&2
  exit 1
fi

if [ "${SOCIAL_DB_SEED_ENABLED:-false}" != "true" ]; then
  echo "Dev social seed disabled; skipping."
  exit 0
fi

until pg_isready -h "${PGHOST}" -p "${PGPORT:-5432}" -U "${PGUSER}" -d "${PGDATABASE}" >/dev/null 2>&1; do
  sleep 2
done

profiles_table_exists="$(psql -tA -v ON_ERROR_STOP=1 -c "select to_regclass('public.profiles') is not null")"
if [ "${profiles_table_exists}" != "t" ]; then
  for migration in /seed/backoffice-migrations/*.sql; do
    [ -f "${migration}" ] || continue
    psql -v ON_ERROR_STOP=1 -f "${migration}"
  done
fi

if [ "${SOCIAL_DB_SEED_FORCE:-false}" = "true" ]; then
  psql -v ON_ERROR_STOP=1 <<'SQL'
truncate table post_reactions, posts, profile_access_grants, profile_follows, profiles cascade;
SQL
fi

profile_count="$(psql -tA -v ON_ERROR_STOP=1 -c "select count(*) from profiles")"
if [ "${profile_count}" != "0" ]; then
  echo "Profiles already present (${profile_count}); skipping dev social seed."
  exit 0
fi

psql -v ON_ERROR_STOP=1 -f /seed/db/dev-social-seed.sql
echo "Seeded dev social database with sample profiles."
