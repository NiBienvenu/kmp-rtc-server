# Architecture et arborescence

## Vue d'ensemble

```text
                 Internet / LAN
   Android  iOS  Desktop  Web
      |      |      |      |
      |  HTTPS (API, WebSocket)          SIP 5060 udp/tcp, 5061 tls
      |  WSS /asterisk/ws (WebRTC)       RTP/SRTP 10000-20000 udp
      |  STUN/TURN 3478                  TURN relais 49160-49200 udp
      v
+------------------------------------ kmp-rtc-server --------------------------------------+
|                                                                                          |
|   Nginx :80/:443 ---------> Ktor :8080 (127.0.0.1) ----JDBC----> MariaDB :3306 (127.0.0.1)|
|     |  /docs /admin           |  ecrit pjsip_users.conf             ^      ^             |
|     |                         |  asterisk -rx (socket CLI)          |      | ODBC        |
|     +--/asterisk/ws--> Asterisk :8088 (127.0.0.1)                   |      |             |
|                        Asterisk PJSIP :5060  ---- CDR -------------+      |             |
|                        RTP 10000-20000       ---- salles (func_odbc) ------+             |
|                                                                                          |
|   coturn :3478 (STUN/TURN, identifiants temporaires signes par Ktor)                     |
|                                                                                          |
|   Supervisor (PID 1) : mariadb -> asterisk -> ktor -> nginx -> coturn                    |
+------------------------------------------------------------------------------------------+
```

Seuls Nginx, Asterisk (SIP + RTP) et coturn sont exposés. Ktor, MariaDB et le serveur HTTP d'Asterisk écoutent uniquement sur `127.0.0.1`.

## Le "contrat" entre Ktor et Asterisk

Pour que **Ktor évolue sans toucher à Asterisk** (et l'inverse), ils ne partagent que 4 choses simples :

| Élément | Écrit par | Lu par | Détail |
|---|---|---|---|
| `/var/lib/kmp-rtc/asterisk/pjsip_users.conf` | Ktor | Asterisk | Uniquement les identités (nom, mot de passe). Les réglages sont dans les templates `kmp-endpoint`, `kmp-auth`, `kmp-aor` de `pjsip.conf`. |
| Table `cdr` | Asterisk (cdr_adaptive_odbc) | Ktor | Journal des appels |
| Table `conference_rooms` | Ktor | Asterisk (func_odbc) | Numéro, PIN, taille des salles |
| Contexte `kmp-internal` | image | les deux | Plan de numérotation : `600` écho, `8XXXX` salles, `<user>` appel direct |
| `/var/spool/kmp-rtc/recordings` | Asterisk (appels, conférences), Ktor (fichiers envoyés) | Ktor | Enregistrements |

Conséquences :

- Ajouter une route Ktor, un service, une table : aucun changement Asterisk.
- Changer les codecs, le NAT, le SRTP : on modifie `pjsip.conf` (ou les variables d'env), Ktor ne change pas.
- La base est la source de vérité. Au démarrage, Ktor réécrit `pjsip_users.conf` depuis la table `sip_accounts`.

## Arborescence du projet

```text
KMP RTC SERVER/
├── kmp-rtc-server/               # CE PROJET: l'image Docker
│   (voir le detail ci-dessous)
└── Omb-Connect_app/              # application KMP de test (client)
    ├── composeApp/               # Android, iOS, Desktop
    └── ...

kmp-rtc-server/
├── Dockerfile                    # image kmp-rtc-server (multi-étapes)
├── .dockerignore
├── docker-compose.yml            # déploiement (mode bridge, ports publiés)
├── docker-compose.host.yml       # surcharge Linux: network_mode host
├── .env.example                  # toutes les variables d'environnement
├── .github/workflows/
│   └── kmp-rtc-server.yml        # build + push vers GHCR
├── config/                       # monté en lecture seule sur /etc/kmp-rtc
│   ├── certs/                    # fullchain.pem + privkey.pem (TLS)
│   └── asterisk/                 # surcharges *.conf et pjsip_custom.conf
├── docker/
│   ├── asterisk/                 # templates Asterisk (rendus au démarrage)
│   │   ├── asterisk.conf  modules.conf  logger.conf
│   │   ├── pjsip.conf     rtp.conf      extensions.conf
│   │   ├── confbridge.conf http.conf    cdr.conf
│   │   ├── cdr_adaptive_odbc.conf res_odbc.conf func_odbc.conf
│   │   └── manager.conf ari.conf musiconhold.conf ...
│   ├── nginx/                    # nginx.conf + snippets (routes, proxy)
│   ├── mariadb/                  # kmp-rtc.cnf + init/01-schema.sql
│   ├── coturn/turnserver.conf
│   ├── supervisor/supervisord.conf
│   ├── admin/index.html          # interface admin (statique)
│   └── scripts/
│       ├── entrypoint.sh         # secrets, NAT, TLS, rendu des configs, init MariaDB
│       ├── run-ktor.sh           # lance Ktor avec son fichier d'env privé
│       ├── healthcheck.sh
│       └── log-rotate.sh
├── docs/                         # cette documentation (Markdown -> HTML)
└── ktor-server/                  # API Ktor (projet Gradle autonome)
    ├── build.gradle.kts  settings.gradle.kts  gradlew
    └── src/main/kotlin/org/kmprtc/server/
        ├── Application.kt
        ├── config/   (DatabaseFactory, JwtConfig, RtcConfig)
        ├── models/   (User, Voip, Calls, Rtc)
        ├── repositories/ (User, Voip, RtcRepositories)
        ├── services/ (Auth, User, Voip, Ice, Recording, CallInvite, AsteriskProvisioner, AsteriskCli)
        ├── routes/   (Auth, User, Voip, Call, Recording, RtcRoutes, SystemRoutes)
        └── plugins/  (Routing, Security, HTTP, Serialization, StatusPages, ...)
```

## Dans le conteneur

| Chemin | Contenu |
|---|---|
| `/opt/kmp-rtc/ktor` | API Ktor (`bin/server`, `lib/*.jar`) |
| `/opt/kmp-rtc/jre` | Java 21 réduit (jlink) |
| `/opt/kmp-rtc/templates` | templates de configuration |
| `/opt/kmp-rtc/bin` | scripts |
| `/etc/asterisk` | configuration Asterisk générée |
| `/run/kmp-rtc` | fichiers générés à chaque démarrage (env Ktor, certificats, coturn) |
| `/var/lib/kmp-rtc` | **volume** : secrets générés, certificat auto-signé, comptes SIP |
| `/var/lib/mysql` | **volume** : données MariaDB |
| `/var/spool/kmp-rtc/recordings` | **volume** : enregistrements audio/vidéo (appels, conférences, fichiers envoyés) |
| `/var/spool/asterisk` | **volume** : spool interne Asterisk |
| `/var/log/asterisk` | **volume** : journaux Asterisk |
| `/var/log/kmp-rtc` | **volume** : journaux Nginx, Supervisor |
| `/usr/share/kmp-rtc/docs` | cette documentation en HTML |

## Ordre de démarrage

1. `entrypoint.sh` : lit/génère les secrets, détecte le réseau, prépare TLS, génère les fichiers de config, initialise MariaDB si besoin (environ 3 s la première fois, presque rien ensuite).
2. Supervisor démarre, par priorité : MariaDB, Asterisk, Ktor, Nginx, coturn.
3. Ktor attend MariaDB (jusqu'à 30 essais), crée ses tables, puis resynchronise les comptes SIP vers Asterisk.

Si un service s'arrête, Supervisor le relance. Le HEALTHCHECK Docker vérifie `/health/ready` (base + Asterisk) et Nginx.
