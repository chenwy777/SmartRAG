#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$SCRIPT_DIR"
FRONTEND_DIR="${FRONTEND_DIR:-$ROOT_DIR/frontend}"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env}"

read_env_value() {
  local key="$1"
  local default_value="${2:-}"

  if [ -n "${!key:-}" ]; then
    printf '%s' "${!key}"
    return
  fi

  if [ -f "$ENV_FILE" ]; then
    local value
    value="$(awk -F= -v key="$key" '$1 == key { sub(/^[^=]*=/, ""); print; exit }' "$ENV_FILE")"
    if [ -n "$value" ]; then
      printf '%s' "$value"
      return
    fi
  fi

  printf '%s' "$default_value"
}

SERVER_HOST="${DEPLOY_SERVER_HOST:-${SERVER_HOST:-$(read_env_value DEPLOY_SERVER_HOST)}}"
SERVER_USER="${DEPLOY_SERVER_USER:-${SERVER_USER:-$(read_env_value DEPLOY_SERVER_USER root)}}"
SERVER_KEY="${DEPLOY_SERVER_KEY:-${SERVER_KEY:-$(read_env_value DEPLOY_SERVER_KEY)}}"
TARGET_DIR="${DEPLOY_TARGET_DIR:-${TARGET_DIR:-$(read_env_value DEPLOY_TARGET_DIR /home/www/SmartRAG-Front)}}"
BUILD_CMD="${DEPLOY_BUILD_CMD:-${BUILD_CMD:-$(read_env_value DEPLOY_BUILD_CMD 'pnpm build')}}"
SKIP_BUILD="${DEPLOY_SKIP_BUILD:-${SKIP_BUILD:-$(read_env_value DEPLOY_SKIP_BUILD 0)}}"
HEALTHCHECK_URL="${DEPLOY_HEALTHCHECK_URL:-${HEALTHCHECK_URL:-$(read_env_value DEPLOY_HEALTHCHECK_URL https://your-domain.example)}}"
HEALTHCHECK_TIMEOUT="${DEPLOY_HEALTHCHECK_TIMEOUT:-${HEALTHCHECK_TIMEOUT:-$(read_env_value DEPLOY_HEALTHCHECK_TIMEOUT 15)}}"

TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
ARTIFACT_NAME="dist-${TIMESTAMP}.zip"
ARTIFACT_PATH="$ROOT_DIR/$ARTIFACT_NAME"
REMOTE_ARTIFACT_PATH="$TARGET_DIR/$ARTIFACT_NAME"
SSH_OPTS=(
  -i "$SERVER_KEY"
  -o StrictHostKeyChecking=accept-new
)

log() {
  printf '[deploy] %s\n' "$1"
}

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    printf 'Missing required command: %s\n' "$1" >&2
    exit 1
  fi
}

require_cmd pnpm
require_cmd zip
require_cmd ssh
require_cmd scp
require_cmd curl

if [ ! -d "$FRONTEND_DIR" ]; then
  printf 'Frontend directory not found: %s\n' "$FRONTEND_DIR" >&2
  exit 1
fi

if [ -z "$SERVER_HOST" ]; then
  printf 'DEPLOY_SERVER_HOST is required. Set it in %s or export it before running.\n' "$ENV_FILE" >&2
  exit 1
fi

if [ -z "$SERVER_KEY" ]; then
  printf 'DEPLOY_SERVER_KEY is required. Set it in %s or export it before running.\n' "$ENV_FILE" >&2
  exit 1
fi

if [ ! -f "$SERVER_KEY" ]; then
  printf 'SSH key not found: %s\n' "$SERVER_KEY" >&2
  exit 1
fi

cd "$FRONTEND_DIR"

if [ "$SKIP_BUILD" != "1" ]; then
  log "building frontend with: $BUILD_CMD"
  eval "$BUILD_CMD"
else
  log "skipping build because SKIP_BUILD=1"
fi

if [ ! -d "$FRONTEND_DIR/dist" ]; then
  printf 'Build output not found: %s\n' "$FRONTEND_DIR/dist" >&2
  exit 1
fi

rm -f "$ARTIFACT_PATH"

log "creating artifact: $ARTIFACT_NAME"
cd "$FRONTEND_DIR"
zip -qry "$ARTIFACT_PATH" dist

log "ensuring remote target directory exists"
ssh "${SSH_OPTS[@]}" "$SERVER_USER@$SERVER_HOST" "mkdir -p '$TARGET_DIR'"

log "uploading artifact to $SERVER_USER@$SERVER_HOST:$TARGET_DIR"
scp "${SSH_OPTS[@]}" "$ARTIFACT_PATH" "$SERVER_USER@$SERVER_HOST:$REMOTE_ARTIFACT_PATH"

log "replacing remote dist directory"
ssh "${SSH_OPTS[@]}" "$SERVER_USER@$SERVER_HOST" "
  set -e
  command -v unzip >/dev/null 2>&1 || { echo 'unzip is required on remote host' >&2; exit 1; }
  cd '$TARGET_DIR'
  rm -rf dist
  unzip -oq '$REMOTE_ARTIFACT_PATH' -d '$TARGET_DIR'
  rm -f '$REMOTE_ARTIFACT_PATH'
"

log "cleaning up local artifact"
rm -f "$ARTIFACT_PATH"

log "verifying remote dist/index.html"
ssh "${SSH_OPTS[@]}" "$SERVER_USER@$SERVER_HOST" "test -f '$TARGET_DIR/dist/index.html'"

if [ -n "$HEALTHCHECK_URL" ]; then
  log "checking health url: $HEALTHCHECK_URL"
  curl --fail --silent --show-error --location --max-time "$HEALTHCHECK_TIMEOUT" "$HEALTHCHECK_URL" >/dev/null
fi

log "deploy finished"
