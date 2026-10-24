#!/usr/bin/env bash
# Lance l'API Ktor avec son fichier d'environnement (genere par entrypoint.sh, lisible par ktor seulement).
set -euo pipefail
set -a
# shellcheck disable=SC1091
. /run/kmp-rtc/ktor.env
set +a
exec /opt/kmp-rtc/ktor/bin/kmp-rtc-ktor-server
