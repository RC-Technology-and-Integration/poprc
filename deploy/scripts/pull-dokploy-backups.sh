#!/usr/bin/env bash
set -Eeuo pipefail

REMOTE_HOST="${POPRC_BACKUP_REMOTE_HOST:-186.196.9.178}"
REMOTE_PORT="${POPRC_BACKUP_REMOTE_PORT:-1157}"
REMOTE_USER="${POPRC_BACKUP_REMOTE_USER:-paulo}"
REMOTE_DIR="${POPRC_BACKUP_REMOTE_DIR:-/var/backups/poprc-dokploy-export/}"
IDENTITY_FILE="${POPRC_BACKUP_IDENTITY_FILE:-/root/.ssh/poprc-backup}"
DESTINATION="${POPRC_BACKUP_DESTINATION:-/srv/backups/poprc}"
RETENTION_DAYS="${POPRC_BACKUP_RETENTION_DAYS:-60}"
LOCK_FILE="${POPRC_BACKUP_LOCK_FILE:-/run/lock/poprc-backup-pull.lock}"

for command_name in rsync ssh tar sha256sum flock; do
    command -v "$command_name" >/dev/null 2>&1 || {
        echo "Comando obrigatorio nao encontrado: $command_name" >&2
        exit 1
    }
done

if [[ ! -r "$IDENTITY_FILE" ]]; then
    echo "Chave SSH nao encontrada: $IDENTITY_FILE" >&2
    exit 1
fi

install -d -m 0700 "$DESTINATION"
exec 9>"$LOCK_FILE"
flock -n 9 || {
    echo "Ja existe outra sincronizacao em andamento." >&2
    exit 1
}

SSH_COMMAND="ssh -i $IDENTITY_FILE -p $REMOTE_PORT -o BatchMode=yes -o StrictHostKeyChecking=yes"
rsync \
    --archive \
    --partial \
    --ignore-existing \
    --chmod=F600,D700 \
    -e "$SSH_COMMAND" \
    "${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_DIR}" \
    "$DESTINATION/"

find "$DESTINATION" -maxdepth 1 -type f \
    -name 'poprc-dokploy-*.tar.gz' \
    -print0 |
while IFS= read -r -d '' archive; do
    marker="${archive}.verified"
    if [[ -f "$marker" && "$marker" -nt "$archive" ]]; then
        continue
    fi

    verify_dir="$(mktemp -d "${DESTINATION}/.verify-XXXXXX")"
    if ! tar -xzf "$archive" -C "$verify_dir" ||
        ! (cd "$verify_dir" && sha256sum --check SHA256SUMS); then
        rm -rf -- "$verify_dir"
        echo "Falha de integridade no backup: $archive" >&2
        exit 1
    fi
    touch "$marker"
    chmod 0600 "$marker"
    rm -rf -- "$verify_dir"
done

find "$DESTINATION" -maxdepth 1 -type f \
    \( -name 'poprc-dokploy-*.tar.gz' -o -name 'poprc-dokploy-*.tar.gz.verified' \) \
    -mtime "+$RETENTION_DAYS" \
    -delete

echo "Backups sincronizados e verificados em $DESTINATION"
