#!/usr/bin/env bash
# =============================================================================
# Plage RTP elastique (par blocs de RTP_STEP ports, 100 par defaut)
#  - toutes les RTP_CHECK_INTERVAL secondes: compte les ports UDP occupes dans la plage
#  - occupation >= RTP_SCALE_UP_PCT %  : ajoute un bloc (jusqu'a RTP_MAX_END)
#  - occupation <  RTP_SCALE_DOWN_PCT % du bloc precedent et rien d'ouvert au-dela:
#    retire un bloc (jamais sous la plage de depart)
#  - Asterisk relit rtp.conf a chaud (module reload): aucun appel coupe
#
# Seulement utile en network_mode: host. En mode bridge, Docker ne peut pas publier
# de nouveaux ports sans recreer le conteneur: laisser RTP_AUTOSCALE=false.
# =============================================================================
set -u

RTP_CONF=/etc/asterisk/rtp.conf
START="${RTP_START:-10000}"
BASE_END="${RTP_END:-10099}"
STEP="${RTP_STEP:-100}"
MAX_END="${RTP_MAX_END:-20000}"
UP_PCT="${RTP_SCALE_UP_PCT:-80}"
DOWN_PCT="${RTP_SCALE_DOWN_PCT:-30}"
INTERVAL="${RTP_CHECK_INTERVAL:-5}"

log() { echo "$(date -u +%FT%TZ) [rtp-autoscale] $*"; }

# Nombre de sockets UDP ouverts entre $1 et $2 (inclus)
used_ports() {
  ss -Huan "( sport >= :$1 and sport <= :$2 )" 2>/dev/null | wc -l
}

apply_end() {
  local new_end="$1"
  sed -i -E "s/^rtpend[[:space:]]*=.*/rtpend = $new_end/" "$RTP_CONF"
  if asterisk -rx "module reload res_rtp_asterisk.so" >/dev/null 2>&1; then
    log "$2: rtp $START-$new_end (occupes: $3)"
    END="$new_end"
  else
    log "ERROR: asterisk reload failed, rtpend stays $END"
    sed -i -E "s/^rtpend[[:space:]]*=.*/rtpend = $END/" "$RTP_CONF"
  fi
}

END="$BASE_END"
log "start: rtp $START-$END, step=$STEP, max=$MAX_END, up>=${UP_PCT}%, down<${DOWN_PCT}%"

while true; do
  sleep "$INTERVAL"
  size=$(( END - START + 1 ))
  used=$(used_ports "$START" "$END")

  if (( used * 100 >= size * UP_PCT )) && (( END < MAX_END )); then
    new_end=$(( END + STEP ))
    (( new_end > MAX_END )) && new_end="$MAX_END"
    apply_end "$new_end" "scale up" "$used/$size"
  elif (( END > BASE_END )); then
    lower_end=$(( END - STEP ))
    (( lower_end < BASE_END )) && lower_end="$BASE_END"
    lower_size=$(( lower_end - START + 1 ))
    # Retire le dernier bloc seulement s'il est vide (les appels en cours ne bougent pas)
    if (( used * 100 < lower_size * DOWN_PCT )) && (( $(used_ports $(( lower_end + 1 )) "$END") == 0 )); then
      apply_end "$lower_end" "scale down" "$used/$size"
    fi
  fi
done
