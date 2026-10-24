#!/usr/bin/env bash
# Toutes les 24 h: rotation des journaux Asterisk et Nginx, suppression des plus vieux.
set -u
KEEP_DAYS="${LOG_RETENTION_DAYS:-14}"
# 0 = garder les enregistrements pour toujours
REC_DAYS="${RECORDINGS_RETENTION_DAYS:-0}"
REC_DIR="${RECORDINGS_DIR:-/var/spool/kmp-rtc/recordings}"
while true; do
  sleep 86400
  asterisk -rx "logger rotate" >/dev/null 2>&1 || true
  for f in /var/log/kmp-rtc/nginx-access.log; do
    [ -s "$f" ] && mv "$f" "$f.$(date +%Y%m%d)" && gzip -9 "$f.$(date +%Y%m%d)" || true
    # Fichiers vides (en-tete WAV seul: appel non decroche)
  find "$REC_DIR/calls" -type f -name "*.wav" -size -45c -mmin +60 -delete 2>/dev/null || true
  if [ "$REC_DAYS" -gt 0 ] 2>/dev/null; then
    find "$REC_DIR/calls" "$REC_DIR/conferences" -type f -mtime +"$REC_DAYS" -delete 2>/dev/null || true
  fi
done
  kill -USR1 "$(cat /run/nginx.pid 2>/dev/null)" 2>/dev/null || true
  find /var/log/asterisk /var/log/kmp-rtc -type f -name "*.gz" -mtime +"$KEEP_DAYS" -delete 2>/dev/null || true
  # Fichiers vides (en-tete WAV seul: appel non decroche)
  find "$REC_DIR/calls" -type f -name "*.wav" -size -45c -mmin +60 -delete 2>/dev/null || true
  if [ "$REC_DAYS" -gt 0 ] 2>/dev/null; then
    find "$REC_DIR/calls" "$REC_DIR/conferences" -type f -mtime +"$REC_DAYS" -delete 2>/dev/null || true
  fi
done
