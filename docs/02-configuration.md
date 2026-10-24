# Configuration : variables, volumes, ports

Toute la configuration passe par des **variables d'environnement** (fichier `.env` lu par Docker Compose). Les fichiers de config Asterisk, Nginx, coturn et MariaDB sont générés au démarrage à partir de ces variables.

## Secrets

Un secret laissé vide est **généré automatiquement** au premier démarrage, puis gardé dans le volume `kmp-data` (`/var/lib/kmp-rtc/secrets/`). Il reste donc le même après un redémarrage.

Chaque secret peut aussi être lu depuis un fichier (Docker secrets) : `DB_PASSWORD_FILE=/run/secrets/db_password`.

Secrets concernés : `DB_PASSWORD`, `CDR_DB_PASSWORD`, `JWT_SECRET`, `TURN_SECRET`, `WEBHOOK_SECRET`, `ADMIN_PASSWORD`.

Pour lire un secret généré :

```bash
docker exec kmp-rtc-server cat /var/lib/kmp-rtc/secrets/JWT_SECRET
```

## Variables d'environnement

### Identité publique

| Variable | Défaut | Description |
|---|---|---|
| `PUBLIC_HOSTNAME` | `localhost` | Nom DNS du serveur. Utilisé pour TLS, l'émetteur JWT, l'URL WebSocket. |
| `EXTERNAL_IP` | vide | IP que les clients utilisent pour joindre le serveur. **Obligatoire en mode bridge** (sinon pas d'audio). |
| `LOCAL_NETS` | réseau du conteneur | Réseaux "internes" pour Asterisk (`local_net`), séparés par des virgules. |
| `SIP_DOMAIN` | `PUBLIC_HOSTNAME` | Domaine SIP envoyé aux applications. |
| `TZ` | `UTC` | Fuseau horaire. |

### HTTP / TLS

| Variable | Défaut | Description |
|---|---|---|
| `HTTP_PUBLISH_PORT` / `HTTPS_PUBLISH_PORT` | `80` / `443` | Ports publiés sur l'hôte (Compose). |
| `HTTP_REDIRECT_HTTPS` | `false` | `true` : le port 80 redirige vers HTTPS (sauf `/.well-known/acme-challenge/`). |
| `CORS_ALLOWED_HOSTS` | `*` | Hôtes autorisés pour un client Web, ex. `app.example.com`. |
| `NGINX_AUTH_RATE` | `10r/m` | Limite de requêtes par IP sur `/auth/*`. |
| `NGINX_API_RATE` | `30r/s` | Limite de requêtes par IP sur le reste de l'API. |
| `NGINX_CLIENT_MAX_BODY_SIZE` | `10m` | Taille max des requêtes. |

Certificat : placez `fullchain.pem` et `privkey.pem` dans `config/certs/`. Sans eux, un certificat auto-signé est créé (pratique pour tester, refusé par les applis en production).

### SIP / RTP (Asterisk)

| Variable | Défaut | Description |
|---|---|---|
| `SIP_PORT` | `5060` | Port SIP (UDP et TCP). |
| `SIP_TRANSPORT` | `udp` | Transport annoncé aux applis : `udp`, `tcp` ou `tls`. |
| `SIP_TLS_ENABLED` | `false` | Active SIP/TLS sur `SIP_TLS_PORT` (utilise le même certificat que Nginx). |
| `SIP_TLS_PORT` | `5061` | Port SIP/TLS. |
| `RTP_START` / `RTP_END` | `10000` / `20000` | Plage RTP. Doit être la même dans Docker et le pare-feu. |
| `MEDIA_ENCRYPTION` | `sdes` | `sdes` = SRTP si le client le propose, sinon RTP. `no` = jamais. |
| `CODECS_AUDIO` | `opus,g722,ulaw,alaw` | Ordre de préférence audio. |
| `CODECS_VIDEO` | `h264,vp8` | Ordre de préférence vidéo. |
| `CONF_VIDEO_MODE` | `follow_talker` | Conférences : `follow_talker` (SIP natif) ou `sfu` (WebRTC). |
| `CONF_MAX_MEMBERS` | `50` | Taille max par défaut d'une salle. |
| `CONFERENCE_PREFIX` | `8` | Les salles sont `8XXXX`. |
| `ASTERISK_DIALPLAN_CONTEXT` | `kmp-internal` | Contexte des comptes. |
| `ASTERISK_WEBRTC_ENDPOINTS` | `true` | Crée aussi `<user>-web` pour les navigateurs. |
| `ASTERISK_VERBOSE` | `3` | Niveau de détail des logs Asterisk. |
| `ASTERISK_MAXLOAD` | `0` | Refuse les appels si la charge système dépasse ce chiffre (0 = off). |
| `ASTERISK_STUN_SERVER` | vide | STUN utilisé par Asterisk lui-même pour ICE (rarement utile si `EXTERNAL_IP` est défini). |

### STUN / TURN (coturn)

| Variable | Défaut | Description |
|---|---|---|
| `TURN_ENABLED` | `true` | Démarre coturn. |
| `TURN_PORT` | `3478` | Port STUN/TURN (UDP + TCP). |
| `TURN_MIN_PORT` / `TURN_MAX_PORT` | `49160` / `49200` | Ports de relais TURN (environ 1 port par flux relayé). |
| `TURN_SECRET` | généré | Secret partagé Ktor/coturn pour signer les identifiants temporaires. |
| `TURN_CREDENTIAL_TTL` | `86400` | Durée de vie des identifiants TURN (secondes). |
| `TURN_HOST` | `SIP_DOMAIN` | Hôte TURN donné aux applis. |
| `STUN_SERVER` | coturn | STUN donné aux applis (`host:port`). Vide si TURN désactivé : aucun serveur externe n'est utilisé. |

### Base de données (MariaDB)

| Variable | Défaut | Description |
|---|---|---|
| `MARIADB_ENABLED` | `true` | `false` pour utiliser une base externe (`DB_HOST`). |
| `DB_HOST` / `DB_PORT` | `127.0.0.1` / `3306` | Adresse de la base. |
| `DB_NAME` | `kmp_rtc` | Nom de la base. |
| `DB_USER` / `DB_PASSWORD` | `kmp_app` / généré | Compte de l'API (tous droits sur `DB_NAME`). |
| `CDR_DB_USER` / `CDR_DB_PASSWORD` | `asterisk_cdr` / généré | Compte Asterisk : `INSERT,SELECT` sur `cdr`, `SELECT` sur `conference_rooms`. |
| `MARIADB_BIND_ADDRESS` | `127.0.0.1` | Ne pas exposer la base. |
| `MARIADB_INNODB_BUFFER_POOL` | `128M` | Mémoire cache InnoDB (mettre ~50 % de la RAM dédiée à la base). |
| `MARIADB_MAX_CONNECTIONS` | `150` | |

### Enregistrements

| Variable | Défaut | Description |
|---|---|---|
| `RECORD_CALLS` | `true` | Enregistre l'audio de chaque appel décroché (les deux voix). |
| `RECORD_CONFERENCES` | `true` | Enregistre l'audio des conférences. |
| `RECORDING_FORMAT` | `wav` | `wav` (~1 Mo/min), `wav49` (~100 Ko/min), `gsm`, `ogg`. |
| `RECORDINGS_MAX_UPLOAD_MB` | `500` | Taille max d'un fichier audio/vidéo envoyé par une appli. |
| `RECORDINGS_RETENTION_DAYS` | `0` | Supprime les enregistrements Asterisk après N jours (0 = jamais). |

Voir [Enregistrements](11-enregistrements.md).

### API Ktor

| Variable | Défaut | Description |
|---|---|---|
| `JWT_SECRET` | généré | Clé de signature des jetons (32 caractères min). |
| `JWT_EXPIRATION_MS` | `86400000` | Durée de vie d'un jeton (24 h). |
| `JWT_ISSUER` / `JWT_AUDIENCE` | `https://PUBLIC_HOSTNAME` / `kmp-rtc-clients` | |
| `ADMIN_EMAIL` / `ADMIN_PASSWORD` | vide | Crée un compte admin au démarrage s'il n'existe pas. |
| `WEBHOOK_SECRET` | vide | Active `POST /webhooks/{source}` (signature HMAC). |
| `LOG_LEVEL` | `INFO` | `DEBUG`, `INFO`, `WARN`... |
| `JAVA_OPTS` | G1, 35 % RAM | Options JVM. |

## Volumes

| Volume (Compose) | Chemin | À sauvegarder ? |
|---|---|---|
| `mariadb-data` | `/var/lib/mysql` | **Oui** (utilisateurs, CDR, salles) |
| `kmp-data` | `/var/lib/kmp-rtc` | **Oui** (secrets générés, comptes SIP) |
| `recordings` | `/var/spool/kmp-rtc/recordings` | **Oui** (enregistrements audio/vidéo) |
| `asterisk-spool` | `/var/spool/asterisk` | Non |
| `asterisk-logs` | `/var/log/asterisk` | Non (journaux) |
| `kmp-logs` | `/var/log/kmp-rtc` | Non (journaux) |
| `./config` (bind, lecture seule) | `/etc/kmp-rtc` | Oui (vos certificats et surcharges) |
| `./certbot-www` (bind) | `/var/www/certbot` | Non |

Sauvegarde de la base :

```bash
docker exec kmp-rtc-server mariadb-dump --single-transaction kmp_rtc | gzip > kmp_rtc_$(date +%F).sql.gz
```

## Ports

| Port | Protocole | Service | Obligatoire |
|---|---|---|---|
| 80 | TCP | HTTP (API, Let's Encrypt) | Oui |
| 443 | TCP | HTTPS (API, WebSocket, WSS SIP) | Oui en production |
| 5060 | UDP + TCP | SIP | Oui |
| 5061 | TCP | SIP/TLS | Si `SIP_TLS_ENABLED=true` |
| 10000-20000 | UDP | RTP/SRTP audio et vidéo | **Oui** |
| 3478 | UDP + TCP | STUN/TURN | Si `TURN_ENABLED=true` |
| 49160-49200 | UDP | Relais TURN | Si `TURN_ENABLED=true` |

Ports internes (jamais exposés) : 8080 (Ktor), 3306 (MariaDB), 8088 (HTTP Asterisk).

## Personnaliser Asterisk sans reconstruire l'image

- `config/asterisk/pjsip_custom.conf` : **ajouté** à la fin de `pjsip.conf` (trunks SIP, comptes de test).
- `config/asterisk/<fichier>.conf` (ex. `extensions.conf`) : **remplace** le fichier généré. Copiez d'abord celui de l'image :

```bash
docker exec kmp-rtc-server cat /etc/asterisk/extensions.conf > config/asterisk/extensions.conf
```

Puis redémarrez : `docker compose restart`.
