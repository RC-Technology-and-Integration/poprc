#!/usr/bin/env bash
set -Eeuo pipefail

if [[ "${EUID}" -ne 0 ]]; then
    echo "Execute este script como root." >&2
    exit 1
fi

COMPOSE_PREFIX="${POPRC_DOKPLOY_PREFIX:-rc-operations-hub-poprchomologacao-wjonap}"
DATABASE_CONTAINER="${POPRC_DATABASE_CONTAINER:-${COMPOSE_PREFIX}-database-1}"
BACKEND_CONTAINER="${POPRC_BACKEND_CONTAINER:-${COMPOSE_PREFIX}-backend-1}"
EXPORT_DIR="${POPRC_EXPORT_DIR:-/var/backups/poprc-dokploy-export}"
EXPORT_GROUP="${POPRC_EXPORT_GROUP:-poprc-backup}"
RETENTION_DAYS="${POPRC_EXPORT_RETENTION_DAYS:-30}"
ARCHIVE_IMAGE="${POPRC_ARCHIVE_IMAGE:-postgres:16-alpine}"
TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"

command -v docker >/dev/null 2>&1 || {
    echo "Docker nao encontrado." >&2
    exit 1
}
getent group "$EXPORT_GROUP" >/dev/null 2>&1 || {
    echo "Grupo $EXPORT_GROUP nao encontrado." >&2
    exit 1
}

for container in "$DATABASE_CONTAINER" "$BACKEND_CONTAINER"; do
    docker inspect "$container" >/dev/null 2>&1 || {
        echo "Container nao encontrado: $container" >&2
        exit 1
    }
done

install -d -o root -g "$EXPORT_GROUP" -m 0750 "$EXPORT_DIR"
EXPORT_DIR="$(cd "$EXPORT_DIR" && pwd -P)"
STAGE="$(mktemp -d "${EXPORT_DIR}/.stage-${TIMESTAMP}-XXXXXX")"
ARCHIVE_TMP="${EXPORT_DIR}/.poprc-dokploy-${TIMESTAMP}.tar.gz.tmp"
ARCHIVE="${EXPORT_DIR}/poprc-dokploy-${TIMESTAMP}.tar.gz"

cleanup() {
    rm -rf -- "$STAGE"
    rm -f -- "$ARCHIVE_TMP"
}
trap cleanup EXIT

LATEST_DUMP="$(docker exec "$DATABASE_CONTAINER" sh -ec \
    'ls -1t /backups/poprc-*.dump 2>/dev/null | head -n 1')"
if [[ -z "$LATEST_DUMP" ]]; then
    echo "Nenhum dump logico encontrado em /backups." >&2
    exit 1
fi

docker cp "${DATABASE_CONTAINER}:${LATEST_DUMP}" "$STAGE/database.dump" >/dev/null
test -s "$STAGE/database.dump" || {
    echo "O dump copiado esta vazio." >&2
    exit 1
}

docker run --rm \
    --volumes-from "${BACKEND_CONTAINER}:ro" \
    "$ARCHIVE_IMAGE" \
    sh -ec 'tar -C /var/lib/poprc/uploads -czf - .' \
    > "$STAGE/uploads.tar.gz"
test -s "$STAGE/uploads.tar.gz" || {
    echo "O arquivo de uploads esta vazio ou invalido." >&2
    exit 1
}

cat > "$STAGE/manifest.txt" <<EOF
created_at_utc=$TIMESTAMP
database_container=$DATABASE_CONTAINER
database_dump=$LATEST_DUMP
backend_container=$BACKEND_CONTAINER
EOF

(
    cd "$STAGE"
    sha256sum database.dump uploads.tar.gz manifest.txt > SHA256SUMS
    sha256sum --check SHA256SUMS
    tar -czf "$ARCHIVE_TMP" database.dump uploads.tar.gz manifest.txt SHA256SUMS
)

tar -tzf "$ARCHIVE_TMP" >/dev/null
mv "$ARCHIVE_TMP" "$ARCHIVE"
chown root:"$EXPORT_GROUP" "$ARCHIVE"
chmod 0640 "$ARCHIVE"

find "$EXPORT_DIR" -maxdepth 1 -type f \
    -name 'poprc-dokploy-*.tar.gz' \
    -mtime "+$RETENTION_DAYS" \
    -delete

echo "$ARCHIVE"
