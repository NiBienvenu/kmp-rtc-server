#!/usr/bin/env bash
# =============================================================================
# publish.sh : construit et publie l'image kmp-rtc-server sur GitHub (GHCR)
#
# Utilisation :
#   ./publish.sh <version> [utilisateur-github]
#
# Exemples :
#   ./publish.sh 1.0.0                 # utilisateur par defaut : NiBienvenu
#   ./publish.sh 1.0.1 MonOrganisation
#   GHCR_TOKEN=ghp_xxx ./publish.sh 1.0.0      # sans question pour le jeton
#   SKIP_BUILD=1 ./publish.sh 1.0.0            # pousse une image deja construite
#
# Le jeton GitHub doit avoir le droit "write:packages".
# =============================================================================
set -Eeuo pipefail

DEFAULT_OWNER="NiBienvenu"
IMAGE_NAME="kmp-rtc-server"

info() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
ok()   { printf '\033[1;32mOK\033[0m  %s\n' "$*"; }
die()  { printf '\033[1;31mERREUR\033[0m %s\n' "$*" >&2; exit 1; }

# --------------------------------------------------------------- arguments
VERSION="${1:-}"
OWNER_INPUT="${2:-$DEFAULT_OWNER}"

[ -n "$VERSION" ] || die "Version manquante. Exemple : ./publish.sh 1.0.0"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+([.-][A-Za-z0-9]+)?$ ]] \
  || die "Version invalide : '$VERSION' (format attendu : 1.0.0)"

# Docker exige des minuscules dans le nom de l'image (NiBienvenu -> nibienvenu).
# Le nom d'utilisateur pour "docker login" garde sa casse d'origine.
OWNER="$(printf '%s' "$OWNER_INPUT" | tr '[:upper:]' '[:lower:]')"
IMAGE="ghcr.io/${OWNER}/${IMAGE_NAME}"

# Toujours travailler depuis le dossier du projet (la ou se trouve ce script)
cd "$(dirname "$(readlink -f "$0")")"
[ -f Dockerfile ] || die "Dockerfile introuvable dans $(pwd)"

command -v docker >/dev/null || die "Docker n'est pas installe"
docker info >/dev/null 2>&1 || die "Docker ne repond pas (est-il demarre ? etes-vous dans le groupe docker ?)"

REVISION="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"

echo
info "Image    : ${IMAGE}"
info "Tags     : ${VERSION}, latest"
info "Revision : ${REVISION}"
echo

# --------------------------------------------------------------- 1. connexion
info "Connexion a ghcr.io (utilisateur ${OWNER_INPUT})"
if [ -z "${GHCR_TOKEN:-}" ]; then
  read -r -s -p "Jeton GitHub (ghp_..., droit write:packages) : " GHCR_TOKEN
  echo
fi
[ -n "$GHCR_TOKEN" ] || die "Jeton vide"

# "docker login" accepte n'importe quel jeton valide : on verifie les droits AVANT le build,
# sinon l'erreur "permission_denied" n'arrive qu'a la fin, au moment du push.
SCOPES="$(curl -fsS -o /dev/null -D - -H "Authorization: token ${GHCR_TOKEN}" https://api.github.com/user 2>/dev/null \
  | tr -d '\r' | sed -n 's/^[Xx]-[Oo][Aa]uth-[Ss]copes: *//p')" || true
if [ -n "$SCOPES" ]; then
  info "Droits du jeton : ${SCOPES}"
  [[ ",${SCOPES// /}," == *",write:packages,"* ]] || die "Le jeton n'a pas le droit write:packages.
       Creez un jeton *classic* : https://github.com/settings/tokens/new?scopes=write:packages
       et cochez write:packages (read:packages est inclus)."
else
  info "Impossible de lire les droits du jeton (jeton 'fine-grained' ?). Les jetons fine-grained"
  info "ne marchent pas avec ghcr.io : utilisez un jeton *classic* avec write:packages."
fi

printf '%s' "$GHCR_TOKEN" | docker login ghcr.io -u "$OWNER_INPUT" --password-stdin >/dev/null \
  || die "Connexion refusee : verifiez le jeton et le droit write:packages"
unset GHCR_TOKEN
ok "Connecte a ghcr.io"

# --------------------------------------------------------------- 2. construction
if [ "${SKIP_BUILD:-0}" = "1" ]; then
  info "SKIP_BUILD=1 : pas de construction"
  docker image inspect "${IMAGE}:${VERSION}" >/dev/null 2>&1 \
    || die "L'image ${IMAGE}:${VERSION} n'existe pas localement"
else
  info "Construction de l'image (plusieurs minutes la premiere fois)"
  BUILD_ARGS=(
    --build-arg "VERSION=${VERSION}"
    --build-arg "REVISION=${REVISION}"
    -t "${IMAGE}:${VERSION}"
    -t "${IMAGE}:latest"
    .
  )
  # BuildKit si buildx est installe, sinon l'ancien constructeur (compatible avec ce Dockerfile)
  if docker buildx version >/dev/null 2>&1; then
    docker build "${BUILD_ARGS[@]}"
  else
    info "buildx absent : utilisation du constructeur classique (avertissement 'DEPRECATED' normal)"
    DOCKER_BUILDKIT=0 docker build "${BUILD_ARGS[@]}"
  fi
  ok "Image construite"
fi

# --------------------------------------------------------------- 3. envoi
info "Envoi vers ghcr.io"
docker push "${IMAGE}:${VERSION}"
docker push "${IMAGE}:latest"

SIZE="$(docker image inspect "${IMAGE}:${VERSION}" --format '{{.Size}}' | awk '{printf "%.0f Mo", $1/1024/1024}')"

echo
ok "Publie : ${IMAGE}:${VERSION} et ${IMAGE}:latest (${SIZE} decompresse)"
cat <<EOF

Etapes suivantes :
  1. Rendre l'image publique (facultatif) :
     https://github.com/${OWNER_INPUT}?tab=packages  >  ${IMAGE_NAME}  >  Package settings  >  Change visibility
  2. Sur le serveur :
     docker pull ${IMAGE}:${VERSION}
     (dans .env : GHCR_OWNER=${OWNER}  KMP_RTC_TAG=${VERSION})
EOF
