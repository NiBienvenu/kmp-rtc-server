#!/usr/bin/env bash
# HEALTHCHECK Docker: API prete (base + Asterisk) et Nginx repond.
set -eu
curl -fsS --max-time 4 "http://127.0.0.1:${KTOR_PORT:-8080}/health/ready" >/dev/null
curl -fsS --max-time 4 -o /dev/null "http://127.0.0.1:${HTTP_PORT:-80}/docs/"
