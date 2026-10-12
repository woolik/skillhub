#!/usr/bin/env bash
# Build + export the Dongbuddy-internal SkillHub images.
#
# Conventions (keep in sync with deploy/dongbuddy-image-build.md):
#   - tags:  skillhub-server:<tag> / skillhub-web:<tag>   (default: latest)
#   - export: docker save -> <out>/skillhub-server.tar / skillhub-web.tar
#   - the server image bakes SKILLHUB_AUTH_SESSION_BOOTSTRAP_ENABLED=true and
#     SKILLHUB_AUTH_DONGBUDDY_ENABLED=true (see server/Dockerfile); the shared
#     secret SKILLHUB_AUTH_DONGBUDDY_SECRET is ALWAYS runtime-only.
#
# Usage:
#   scripts/build-release-images.sh [--tag TAG] [--out DIR] [--skip-tests] [--no-save]
#
# Env overrides: TAG, OUT_DIR, SKIP_TESTS, NO_SAVE.
set -euo pipefail
cd "$(dirname "$0")/.."

TAG="${TAG:-latest}"
OUT_DIR="${OUT_DIR:-.}"
SKIP_TESTS="${SKIP_TESTS:-0}"
NO_SAVE="${NO_SAVE:-0}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --tag) TAG="$2"; shift 2 ;;
    --out) OUT_DIR="$2"; shift 2 ;;
    --skip-tests) SKIP_TESTS=1; shift ;;
    --no-save) NO_SAVE=1; shift ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done

echo "==> skillhub image build (tag=$TAG out=$OUT_DIR tests=$([ "$SKIP_TESTS" = 1 ] && echo off || echo on))"

if [[ "$SKIP_TESTS" != 1 ]]; then
  echo "==> [1/4] skillhub-auth unit tests (containerized, no local JVM needed)"
  docker run --rm -v "$PWD/server":/w -w /w eclipse-temurin:21-jdk-alpine \
    sh -c "./mvnw -B -pl skillhub-auth -am test"
else
  echo "==> [1/4] tests skipped (--skip-tests)"
fi

echo "==> [2/4] docker build skillhub-server:$TAG (context server/, first run downloads maven deps ~10-20min)"
docker build -t "skillhub-server:$TAG" server/

echo "==> [3/4] docker build skillhub-web:$TAG (context web/)"
docker build -t "skillhub-web:$TAG" web/

if [[ "$NO_SAVE" != 1 ]]; then
  echo "==> [4/4] docker save tars into $OUT_DIR"
  mkdir -p "$OUT_DIR"
  docker save -o "$OUT_DIR/skillhub-server.tar" "skillhub-server:$TAG"
  docker save -o "$OUT_DIR/skillhub-web.tar" "skillhub-web:$TAG"
  ls -lh "$OUT_DIR"/skillhub-*.tar
else
  echo "==> [4/4] save skipped (--no-save)"
fi

cat <<EOF

Done. Deploy hint (target host):
  docker load -i skillhub-server.tar && docker load -i skillhub-web.tar
  SKILLHUB_SERVER_IMAGE=skillhub-server SKILLHUB_WEB_IMAGE=skillhub-web SKILLHUB_VERSION=$TAG \\
  SKILLHUB_AUTH_DONGBUDDY_SECRET=<same value as Dongbuddy gateway SKILLHUB_SSO_SECRET> \\
  docker compose -f compose.release.yml up -d
EOF
