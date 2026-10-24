#!/usr/bin/env bash
# =============================================================================
# kmp-rtc-server: entrypoint
#  1. lit les secrets (VAR ou VAR_FILE), genere ceux qui manquent (persistes dans le volume)
#  2. detecte le reseau (IP du conteneur, IP publique, reseaux locaux) pour le NAT
#  3. prepare les certificats TLS (fournis ou auto-signes)
#  4. genere les configs (Asterisk, Nginx, coturn, ODBC, MariaDB, Ktor) depuis les templates
#  5. initialise MariaDB au premier demarrage (base, utilisateurs, schema partage)
#  6. lance Supervisor (qui lance tous les services)
# =============================================================================
set -Eeuo pipefail

KMP_HOME=/opt/kmp-rtc
TEMPLATES="$KMP_HOME/templates"
DATA=/var/lib/kmp-rtc
RUN=/run/kmp-rtc
CONF=/etc/kmp-rtc

log()  { echo "$(date -u +%FT%TZ) [entrypoint] $*"; }
warn() { echo "$(date -u +%FT%TZ) [entrypoint] WARNING: $*" >&2; }
die()  { echo "$(date -u +%FT%TZ) [entrypoint] ERROR: $*" >&2; exit 1; }

bool() { case "${1,,}" in 1|true|yes|on) echo true ;; *) echo false ;; esac; }

# ----------------------------------------------------------------- 1. secrets
# Docker secrets: X_FILE=/run/secrets/x remplace X
for v in DB_PASSWORD CDR_DB_PASSWORD JWT_SECRET TURN_SECRET WEBHOOK_SECRET ADMIN_PASSWORD; do
  f="${v}_FILE"
  if [ -n "${!f:-}" ]; then
    [ -r "${!f}" ] || die "$f=${!f} is not readable"
    export "$v=$(<"${!f}")"
  fi
done

install -d -m 0755 "$DATA" "$RUN"
install -d -m 0700 "$DATA/secrets"

# Genere un secret aleatoire une seule fois, puis le relit a chaque demarrage.
secret() {
  local name="$1" file="$DATA/secrets/$1"
  if [ -n "${!name:-}" ]; then return; fi
  if [ ! -s "$file" ]; then
    openssl rand -hex 24 > "$file"
    chmod 600 "$file"
    log "generated secret $name (stored in $file)"
  fi
  export "$name=$(<"$file")"
}
secret DB_PASSWORD
secret CDR_DB_PASSWORD
secret JWT_SECRET
secret TURN_SECRET
[ "${#JWT_SECRET}" -ge 32 ] || die "JWT_SECRET must be at least 32 characters"

# ----------------------------------------------------------------- defaults
export TZ="${TZ:-UTC}"
export PUBLIC_HOSTNAME="${PUBLIC_HOSTNAME:-localhost}"
export HTTP_PORT="${HTTP_PORT:-80}"
export HTTPS_PORT="${HTTPS_PORT:-443}"
export HTTP_REDIRECT_HTTPS="$(bool "${HTTP_REDIRECT_HTTPS:-false}")"
export KTOR_PORT="${KTOR_PORT:-8080}"
export SIP_PORT="${SIP_PORT:-5060}"
export SIP_TLS_ENABLED="$(bool "${SIP_TLS_ENABLED:-false}")"
export SIP_TLS_PORT="${SIP_TLS_PORT:-5061}"
export SIP_TRANSPORT="${SIP_TRANSPORT:-udp}"
export RTP_START="${RTP_START:-10000}"
export RTP_END="${RTP_END:-20000}"
export ASTERISK_HTTP_PORT="${ASTERISK_HTTP_PORT:-8088}"
export ASTERISK_VERBOSE="${ASTERISK_VERBOSE:-3}"
export ASTERISK_MAXLOAD="${ASTERISK_MAXLOAD:-0}"
export ASTERISK_DIALPLAN_CONTEXT="${ASTERISK_DIALPLAN_CONTEXT:-kmp-internal}"
export CONFERENCE_PREFIX="${CONFERENCE_PREFIX:-8}"
export CONF_VIDEO_MODE="${CONF_VIDEO_MODE:-follow_talker}"
export CONF_MAX_MEMBERS="${CONF_MAX_MEMBERS:-50}"
export CODECS_AUDIO="${CODECS_AUDIO:-opus,g722,ulaw,alaw}"
export CODECS_VIDEO="${CODECS_VIDEO:-h264,vp8}"
export MEDIA_ENCRYPTION="${MEDIA_ENCRYPTION:-sdes}"
export MARIADB_ENABLED="$(bool "${MARIADB_ENABLED:-true}")"
export MARIADB_BIND_ADDRESS="${MARIADB_BIND_ADDRESS:-127.0.0.1}"
export MARIADB_INNODB_BUFFER_POOL="${MARIADB_INNODB_BUFFER_POOL:-128M}"
export MARIADB_MAX_CONNECTIONS="${MARIADB_MAX_CONNECTIONS:-150}"
export DB_HOST="${DB_HOST:-127.0.0.1}"
export DB_PORT="${DB_PORT:-3306}"
export DB_NAME="${DB_NAME:-kmp_rtc}"
export DB_USER="${DB_USER:-kmp_app}"
export CDR_DB_USER="${CDR_DB_USER:-asterisk_cdr}"
export TURN_ENABLED="$(bool "${TURN_ENABLED:-true}")"
export TURN_PORT="${TURN_PORT:-3478}"
export TURN_MIN_PORT="${TURN_MIN_PORT:-49160}"
export TURN_MAX_PORT="${TURN_MAX_PORT:-49200}"
export TURN_TOTAL_QUOTA="${TURN_TOTAL_QUOTA:-200}"
export NGINX_WORKER_PROCESSES="${NGINX_WORKER_PROCESSES:-auto}"
export NGINX_CLIENT_MAX_BODY_SIZE="${NGINX_CLIENT_MAX_BODY_SIZE:-10m}"
export NGINX_AUTH_RATE="${NGINX_AUTH_RATE:-10r/m}"
export NGINX_API_RATE="${NGINX_API_RATE:-30r/s}"
export RECORDINGS_DIR="${RECORDINGS_DIR:-/var/spool/kmp-rtc/recordings}"
export RECORDING_FORMAT="${RECORDING_FORMAT:-wav}"
export RECORD_CALLS="$( [ "$(bool "${RECORD_CALLS:-true}")" = true ] && echo yes || echo no )"
export RECORD_CONFERENCES="$( [ "$(bool "${RECORD_CONFERENCES:-true}")" = true ] && echo yes || echo no )"
export RECORDINGS_MAX_UPLOAD_MB="${RECORDINGS_MAX_UPLOAD_MB:-500}"

for v in DB_NAME DB_USER CDR_DB_USER; do
  [[ "${!v}" =~ ^[A-Za-z0-9_]{1,32}$ ]] || die "$v must match [A-Za-z0-9_]{1,32}"
done
[[ "$RECORDING_FORMAT" =~ ^(wav|wav49|gsm|ogg)$ ]] || die "RECORDING_FORMAT must be wav, wav49, gsm or ogg"
[[ "$SIP_TRANSPORT" =~ ^(udp|tcp|tls)$ ]] || die "SIP_TRANSPORT must be udp, tcp or tls"
[ "$RTP_START" -lt "$RTP_END" ] || die "RTP_START must be lower than RTP_END"
if [ "$SIP_TRANSPORT" = tls ] && [ "$SIP_TLS_ENABLED" != true ]; then
  die "SIP_TRANSPORT=tls needs SIP_TLS_ENABLED=true"
fi

# ----------------------------------------------------------------- 2. reseau / NAT
CONTAINER_IP="$(ip -4 -o addr show scope global 2>/dev/null | awk '{split($4,a,"/"); print a[1]; exit}')"
CONTAINER_IP="${CONTAINER_IP:-127.0.0.1}"
export CONTAINER_IP

# Reseaux du conteneur (ex: 172.18.0.0/16 en mode bridge, le LAN en mode host)
container_nets() {
  ip -4 route show scope link 2>/dev/null | awk '$1 ~ /\// {print $1}' | paste -sd, -
}

export EXTERNAL_IP="${EXTERNAL_IP:-}"
export LOCAL_NETS="${LOCAL_NETS:-$(container_nets)}"
LOCAL_NETS="${LOCAL_NETS:-172.16.0.0/12}"

# Domaine SIP communique aux applis: DNS public, sinon IP publique, sinon IP du conteneur
if [ -z "${SIP_DOMAIN:-}" ]; then
  if [ "$PUBLIC_HOSTNAME" != localhost ]; then SIP_DOMAIN="$PUBLIC_HOSTNAME"
  elif [ -n "$EXTERNAL_IP" ]; then SIP_DOMAIN="$EXTERNAL_IP"
  else SIP_DOMAIN="$CONTAINER_IP"; fi
fi
export SIP_DOMAIN

PJSIP_NAT=""
RTP_ICE_HOST_CANDIDATES=""
if [ -n "$EXTERNAL_IP" ]; then
  PJSIP_NAT="external_media_address = $EXTERNAL_IP"$'\n'"external_signaling_address = $EXTERNAL_IP"
  IFS=',' read -ra nets <<< "$LOCAL_NETS"
  for n in "${nets[@]}" 127.0.0.0/8; do
    PJSIP_NAT+=$'\n'"local_net = ${n// /}"
  done
  if [ "$EXTERNAL_IP" != "$CONTAINER_IP" ]; then
    RTP_ICE_HOST_CANDIDATES="[ice_host_candidates]"$'\n'"$CONTAINER_IP => $EXTERNAL_IP"
  fi
  log "NAT: external=$EXTERNAL_IP container=$CONTAINER_IP local_net=$LOCAL_NETS"
else
  warn "EXTERNAL_IP is empty: no NAT rewrite. Fine with network_mode=host on a LAN;"
  warn "in bridge mode or behind a router set EXTERNAL_IP to the IP clients use (else one-way/no audio)."
fi
export PJSIP_NAT RTP_ICE_HOST_CANDIDATES
export RTP_STUN="${ASTERISK_STUN_SERVER:+stunaddr = $ASTERISK_STUN_SERVER}"

# STUN donne aux applis: coturn integre (aucun serveur externe: fonctionne sans Internet)
if [ -z "${STUN_SERVER:-}" ] && [ "$TURN_ENABLED" = true ]; then
  STUN_SERVER="$SIP_DOMAIN:$TURN_PORT"
fi
STUN_SERVER="${STUN_SERVER:-}"
export STUN_SERVER
export TURN_HOST="${TURN_HOST:-$SIP_DOMAIN}"
export TURN_REALM="${TURN_REALM:-$PUBLIC_HOSTNAME}"
export TURN_EXTERNAL_IP="${EXTERNAL_IP:+external-ip=$EXTERNAL_IP/$CONTAINER_IP}"
export TURN_LISTENING_IP=""
TURN_ALLOWED_PEER="allowed-peer-ip=$CONTAINER_IP"
[ -n "$EXTERNAL_IP" ] && TURN_ALLOWED_PEER+=$'\n'"allowed-peer-ip=$EXTERNAL_IP"
export TURN_ALLOWED_PEER

# ----------------------------------------------------------------- 3. TLS
install -d -m 0750 -g asterisk "$RUN/certs"
if [ -s "$CONF/certs/fullchain.pem" ] && [ -s "$CONF/certs/privkey.pem" ]; then
  cp "$CONF/certs/fullchain.pem" "$RUN/certs/fullchain.pem"
  cp "$CONF/certs/privkey.pem" "$RUN/certs/privkey.pem"
  log "TLS: using provided certificate from $CONF/certs"
else
  install -d -m 0700 "$DATA/certs"
  if [ ! -s "$DATA/certs/fullchain.pem" ]; then
    san="DNS:$PUBLIC_HOSTNAME,DNS:localhost,IP:127.0.0.1"
    [ -n "$EXTERNAL_IP" ] && san+=",IP:$EXTERNAL_IP"
    openssl req -x509 -nodes -newkey rsa:2048 -days 825 \
      -keyout "$DATA/certs/privkey.pem" -out "$DATA/certs/fullchain.pem" \
      -subj "/CN=$PUBLIC_HOSTNAME" -addext "subjectAltName=$san" >/dev/null 2>&1
    log "TLS: generated a self-signed certificate (mount config/certs/fullchain.pem + privkey.pem for production)"
  fi
  cp "$DATA/certs/fullchain.pem" "$DATA/certs/privkey.pem" "$RUN/certs/"
fi
chown root:asterisk "$RUN/certs/"*.pem
chmod 0640 "$RUN/certs/"*.pem
export TLS_CERT_FILE="$RUN/certs/fullchain.pem"
export TLS_KEY_FILE="$RUN/certs/privkey.pem"

PJSIP_TLS_TRANSPORT=""
if [ "$SIP_TLS_ENABLED" = true ]; then
  PJSIP_TLS_TRANSPORT="[transport-tls]
type = transport
protocol = tls
bind = 0.0.0.0:$SIP_TLS_PORT
cert_file = $TLS_CERT_FILE
priv_key_file = $TLS_KEY_FILE
method = tlsv1_2
tos = cs3
cos = 3
$PJSIP_NAT"
fi
export PJSIP_TLS_TRANSPORT

if [ "$HTTP_REDIRECT_HTTPS" = true ]; then
  NGINX_HTTP_BODY='location / { return 301 https://$host$request_uri; }'
else
  NGINX_HTTP_BODY='include /etc/nginx/snippets/kmp-locations.conf;'
fi
export NGINX_HTTP_BODY

# ----------------------------------------------------------------- 4. configs
# Seules ces variables sont remplacees: ${EXTEN}, $host, etc. restent intacts.
VARS='${SIP_PORT} ${SIP_TLS_PORT} ${RTP_START} ${RTP_END} ${PJSIP_NAT} ${PJSIP_TLS_TRANSPORT}
${RTP_STUN} ${RTP_ICE_HOST_CANDIDATES} ${ASTERISK_DIALPLAN_CONTEXT} ${CONFERENCE_PREFIX}
${CODECS_AUDIO} ${CODECS_VIDEO} ${MEDIA_ENCRYPTION} ${CONF_VIDEO_MODE} ${CONF_MAX_MEMBERS}
${ASTERISK_HTTP_PORT} ${ASTERISK_VERBOSE} ${ASTERISK_MAXLOAD} ${CDR_DB_USER} ${CDR_DB_PASSWORD}
${HTTP_PORT} ${HTTPS_PORT} ${PUBLIC_HOSTNAME} ${KTOR_PORT} ${TLS_CERT_FILE} ${TLS_KEY_FILE}
${NGINX_HTTP_BODY} ${NGINX_WORKER_PROCESSES} ${NGINX_CLIENT_MAX_BODY_SIZE} ${NGINX_AUTH_RATE} ${NGINX_API_RATE}
${TURN_PORT} ${TURN_LISTENING_IP} ${TURN_EXTERNAL_IP} ${TURN_REALM} ${TURN_SECRET} ${TURN_MIN_PORT}
${TURN_MAX_PORT} ${TURN_TOTAL_QUOTA} ${TURN_ALLOWED_PEER}
${MARIADB_BIND_ADDRESS} ${DB_PORT} ${MARIADB_INNODB_BUFFER_POOL} ${MARIADB_MAX_CONNECTIONS}
${RECORDINGS_DIR} ${RECORDING_FORMAT} ${RECORD_CALLS} ${RECORD_CONFERENCES} ${RECORDINGS_MAX_UPLOAD_MB}'

render() { envsubst "$VARS" < "$1" > "$2"; }

# Asterisk
for f in "$TEMPLATES"/asterisk/*.conf; do
  render "$f" "/etc/asterisk/$(basename "$f")"
done
# Surcharges: config/asterisk/<fichier>.conf remplace le fichier genere (sauf pjsip_custom.conf, inclus)
if [ -d "$CONF/asterisk" ]; then
  for f in "$CONF"/asterisk/*.conf; do
    [ -e "$f" ] || continue
    [ "$(basename "$f")" = pjsip_custom.conf ] && continue
    cp "$f" "/etc/asterisk/$(basename "$f")"
    log "asterisk: override $(basename "$f") from $CONF/asterisk"
  done
fi
chown -R root:asterisk /etc/asterisk
chmod 0750 /etc/asterisk
chmod 0640 /etc/asterisk/*.conf

install -d -o asterisk -g asterisk -m 0755 /var/run/asterisk /var/log/asterisk /var/spool/asterisk \
  /var/lib/asterisk /var/lib/asterisk/moh /var/cache/asterisk
# Dossier partage Asterisk/Ktor (setgid: les fichiers crees par ktor restent dans le groupe asterisk)
install -d -o asterisk -g asterisk -m 2775 "$DATA/asterisk"
touch "$DATA/asterisk/pjsip_users.conf"
chown asterisk:asterisk "$DATA/asterisk/pjsip_users.conf"
chmod 0664 "$DATA/asterisk/pjsip_users.conf"
chown -R asterisk:asterisk /var/log/asterisk /var/spool/asterisk /var/lib/asterisk
# Enregistrements: Asterisk ecrit calls/ et conferences/, Ktor (groupe asterisk) ecrit uploads/
install -d -o asterisk -g asterisk -m 2775 "$RECORDINGS_DIR" "$RECORDINGS_DIR/calls" \
  "$RECORDINGS_DIR/conferences" "$RECORDINGS_DIR/uploads"

# Nginx
render "$TEMPLATES/nginx/nginx.conf" /etc/nginx/nginx.conf
install -d /etc/nginx/snippets /var/www/certbot
for f in "$TEMPLATES"/nginx/kmp-*.conf; do render "$f" "/etc/nginx/snippets/$(basename "$f")"; done
install -d -m 0755 /var/log/kmp-rtc

# coturn
render "$TEMPLATES/coturn/turnserver.conf" "$RUN/turnserver.conf"
chown root:turnserver "$RUN/turnserver.conf"
chmod 0640 "$RUN/turnserver.conf"
install -d -o turnserver -g turnserver -m 0755 "$RUN/turn"

# ODBC (Asterisk -> MariaDB)
if [ "$MARIADB_ENABLED" = true ] && [[ "$DB_HOST" =~ ^(127\.0\.0\.1|localhost)$ ]]; then
  odbc_conn="Server = localhost
Socket = /run/mysqld/mysqld.sock"
else
  odbc_conn="Server = $DB_HOST
Port = $DB_PORT"
fi
cat > /etc/odbc.ini <<EOF_ODBC
[kmp-mariadb]
Description = kmp-rtc-server MariaDB
Driver = MariaDB Unicode
$odbc_conn
Database = $DB_NAME
Charset = utf8mb4
Option = 3
EOF_ODBC

# Ktor: fichier d'environnement prive
umask 077
{
  printf '%s=%q\n' KTOR_HOST 127.0.0.1 KTOR_PORT "$KTOR_PORT"
  printf '%s=%q\n' DB_HOST "$DB_HOST" DB_PORT "$DB_PORT" DB_NAME "$DB_NAME" DB_USER "$DB_USER" DB_PASSWORD "$DB_PASSWORD"
  printf '%s=%q\n' JWT_SECRET "$JWT_SECRET" JWT_ISSUER "${JWT_ISSUER:-https://$PUBLIC_HOSTNAME}" \
    JWT_AUDIENCE "${JWT_AUDIENCE:-kmp-rtc-clients}" JWT_EXPIRATION_MS "${JWT_EXPIRATION_MS:-86400000}"
  printf '%s=%q\n' SIP_DOMAIN "$SIP_DOMAIN" SIP_TRANSPORT "$SIP_TRANSPORT" STUN_SERVER "$STUN_SERVER"
  printf '%s=%q\n' TURN_ENABLED "$TURN_ENABLED" TURN_HOST "$TURN_HOST" TURN_PORT "$TURN_PORT" \
    TURN_SECRET "$TURN_SECRET" TURN_CREDENTIAL_TTL "${TURN_CREDENTIAL_TTL:-86400}"
  printf '%s=%q\n' SIP_WS_URI "${SIP_WS_URI:-wss://$PUBLIC_HOSTNAME/asterisk/ws}" CONFERENCE_PREFIX "$CONFERENCE_PREFIX"
  printf '%s=%q\n' ASTERISK_DIALPLAN_CONTEXT "$ASTERISK_DIALPLAN_CONTEXT" \
    ASTERISK_PJSIP_INCLUDE_FILE "$DATA/asterisk/pjsip_users.conf" \
    ASTERISK_WEBRTC_ENDPOINTS "$(bool "${ASTERISK_WEBRTC_ENDPOINTS:-true}")"
  printf '%s=%q\n' CORS_ALLOWED_HOSTS "${CORS_ALLOWED_HOSTS:-*}" WEBHOOK_SECRET "${WEBHOOK_SECRET:-}" \
    ADMIN_EMAIL "${ADMIN_EMAIL:-}" ADMIN_PASSWORD "${ADMIN_PASSWORD:-}" \
    LOG_LEVEL "${LOG_LEVEL:-INFO}" RECORDINGS_DIR "$RECORDINGS_DIR" RECORDINGS_MAX_UPLOAD_MB "$RECORDINGS_MAX_UPLOAD_MB"
  printf '%s=%q\n' JAVA_HOME "$KMP_HOME/jre" TZ "$TZ" \
    JAVA_OPTS "${JAVA_OPTS:--XX:+UseG1GC -XX:MaxRAMPercentage=35 -XX:+ExitOnOutOfMemoryError -XX:+UseStringDeduplication -Xss512k -Dfile.encoding=UTF-8}"
} > "$RUN/ktor.env"
umask 022
chown root:ktor "$RUN/ktor.env"
chmod 0640 "$RUN/ktor.env"

# ----------------------------------------------------------------- 5. MariaDB
sql_escape() { local s="${1//\\/\\\\}"; printf '%s' "${s//\'/\'\'}"; }

init_mariadb() {
  render "$TEMPLATES/mariadb/kmp-rtc.cnf" /etc/mysql/kmp-rtc.cnf
  install -d -o mysql -g mysql -m 0755 /run/mysqld /var/log/kmp-rtc/mysql
  chown -R mysql:mysql /var/lib/mysql

  local fresh=false
  if [ ! -d /var/lib/mysql/mysql ]; then
    log "MariaDB: initializing data directory"
    mariadb-install-db --defaults-file=/etc/mysql/kmp-rtc.cnf --user=mysql --datadir=/var/lib/mysql \
      --auth-root-authentication-method=socket --skip-test-db >/dev/null
    fresh=true
  fi

  # Ne relance la config SQL que si quelque chose a change (demarrage rapide).
  local hash marker=/var/lib/mysql/.kmp-rtc-init
  hash="$(printf '%s|' "$DB_NAME" "$DB_USER" "$DB_PASSWORD" "$CDR_DB_USER" "$CDR_DB_PASSWORD" \
    | cat - "$TEMPLATES"/mariadb/init/*.sql | sha256sum | cut -d' ' -f1)"
  if [ "$fresh" = false ] && [ -f "$marker" ] && [ "$(<"$marker")" = "$hash" ]; then
    return
  fi

  log "MariaDB: applying users and schema"
  local sock=/run/mysqld/init.sock
  mariadbd --defaults-file=/etc/mysql/kmp-rtc.cnf --user=mysql --skip-networking --socket="$sock" &
  local pid=$!
  for _ in $(seq 1 60); do
    mariadb-admin --socket="$sock" ping >/dev/null 2>&1 && break
    sleep 0.5
  done
  mariadb-admin --socket="$sock" ping >/dev/null 2>&1 || die "MariaDB did not start"

  local app_pw cdr_pw
  app_pw="$(sql_escape "$DB_PASSWORD")"
  cdr_pw="$(sql_escape "$CDR_DB_PASSWORD")"
  mariadb --socket="$sock" -uroot <<EOF_SQL
DROP USER IF EXISTS ''@'localhost';
DROP USER IF EXISTS ''@'$(hostname)';
CREATE DATABASE IF NOT EXISTS \`$DB_NAME\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS '$DB_USER'@'localhost' IDENTIFIED BY '$app_pw';
CREATE USER IF NOT EXISTS '$DB_USER'@'127.0.0.1' IDENTIFIED BY '$app_pw';
ALTER USER '$DB_USER'@'localhost' IDENTIFIED BY '$app_pw';
ALTER USER '$DB_USER'@'127.0.0.1' IDENTIFIED BY '$app_pw';
GRANT ALL PRIVILEGES ON \`$DB_NAME\`.* TO '$DB_USER'@'localhost';
GRANT ALL PRIVILEGES ON \`$DB_NAME\`.* TO '$DB_USER'@'127.0.0.1';
USE \`$DB_NAME\`;
$(cat "$TEMPLATES"/mariadb/init/*.sql)
CREATE USER IF NOT EXISTS '$CDR_DB_USER'@'localhost' IDENTIFIED BY '$cdr_pw';
ALTER USER '$CDR_DB_USER'@'localhost' IDENTIFIED BY '$cdr_pw';
GRANT SELECT, INSERT ON \`$DB_NAME\`.cdr TO '$CDR_DB_USER'@'localhost';
GRANT SELECT ON \`$DB_NAME\`.conference_rooms TO '$CDR_DB_USER'@'localhost';
FLUSH PRIVILEGES;
EOF_SQL

  mariadb-admin --socket="$sock" shutdown
  wait "$pid" || true
  echo "$hash" > "$marker"
  log "MariaDB: ready"
}

if [ "$MARIADB_ENABLED" = true ]; then
  init_mariadb
else
  log "MariaDB disabled: using external database $DB_HOST:$DB_PORT/$DB_NAME"
fi

# Le fichier contenant les secrets n'est pas utile aux autres services
unset DB_PASSWORD CDR_DB_PASSWORD JWT_SECRET TURN_SECRET WEBHOOK_SECRET ADMIN_PASSWORD

log "starting services: public=$PUBLIC_HOSTNAME sip=$SIP_DOMAIN:$SIP_PORT/$SIP_TRANSPORT rtp=$RTP_START-$RTP_END turn=$TURN_ENABLED record_calls=$RECORD_CALLS record_conferences=$RECORD_CONFERENCES"
exec "$@"
