# Licences open source et utilisation hors ligne

## Tout est open source

| Composant | Rôle | Licence |
|---|---|---|
| Ubuntu 24.04 (paquets) | Système de base | Licences libres (GPL, MIT, BSD...) |
| Asterisk 20 | SIP, RTP, conférences, enregistrement | GPL-2.0 |
| codec_opus_open_source / libopus | Codec Opus | BSD-3 |
| Sons `asterisk-core-sounds-en-gsm` | Messages vocaux | CC-BY-SA 3.0 |
| MariaDB 10.11 | Base de données | GPL-2.0 |
| MariaDB Connector/ODBC + unixODBC | Lien Asterisk -> MariaDB | LGPL-2.1 |
| Nginx | Reverse proxy, TLS | BSD-2 |
| coturn | STUN / TURN | BSD-3 |
| Supervisor | Gestion des processus | BSD-like (Repoze) |
| OpenSSL | TLS, certificats | Apache-2.0 |
| Eclipse Temurin (OpenJDK 21) | Java | GPL-2.0 + Classpath Exception |
| Kotlin, kotlinx | Langage | Apache-2.0 |
| Ktor | API REST | Apache-2.0 |
| Exposed | Accès base de données | Apache-2.0 |
| HikariCP | Pool de connexions | Apache-2.0 |
| MariaDB Connector/J | Pilote JDBC | LGPL-2.1 |
| java-jwt (Auth0) | Jetons JWT | MIT |
| jBCrypt | Hachage des mots de passe | ISC |
| Logback / SLF4J | Journaux | EPL-1.0 / LGPL-2.1 / MIT |
| Pandoc (build seulement) | Génère cette documentation | GPL-2.0 |

**WebRTC** est un standard ouvert (W3C/IETF). Côté serveur, il est géré par Asterisk (`res_pjsip_transport_websocket`, `res_srtp`, DTLS), tout en GPL-2.0. Côté navigateur, il est intégré au navigateur. Les bibliothèques conseillées pour un client Web (JsSIP, SIP.js) sont sous licence MIT.

**Ce qui a été retiré** pour rester 100 % open source et hors ligne :

- Firebase Admin SDK / FCM (service Google) : remplacé par le WebSocket `/ws` de l'API pour les appels entrants ;
- serveur STUN de Google : remplacé par coturn intégré ;
- détection d'IP publique via un site externe : l'IP se règle avec `EXTERNAL_IP` ;
- connexion SSH vers un Asterisk distant : Asterisk est dans l'image.

Côté application de test (Omb-Connect) : la dépendance Android `firebase-messaging` utilise les services Google Play (non libres, et besoin d'Internet). Elle ne sert pas avec cette image ; vous pouvez la retirer de l'app.

## Installer sur un serveur sans Internet

Il faut Internet **une seule fois**, sur une autre machine, pour construire ou télécharger l'image. Ensuite, le serveur n'a plus besoin d'Internet.

### 1. Sur une machine connectée

```bash
# Construire (ou docker pull ghcr.io/<organisation>/kmp-rtc-server:1.0.0)
cd kmp-rtc-server
docker build -t kmp-rtc-server:1.0.0 .

# Exporter l'image dans un fichier
docker save kmp-rtc-server:1.0.0 | gzip > kmp-rtc-server_1.0.0.tar.gz
```

Copiez sur une clé USB : `kmp-rtc-server_1.0.0.tar.gz`, `docker-compose.yml`, `docker-compose.host.yml`, `.env.example`, le dossier `config/`.

Si le serveur n'a pas Docker, téléchargez aussi les paquets `.deb` de Docker (<https://download.docker.com/linux/ubuntu/dists/>) : `containerd.io`, `docker-ce`, `docker-ce-cli`, `docker-buildx-plugin`, `docker-compose-plugin`, et installez-les avec `sudo dpkg -i *.deb`.

### 2. Sur le serveur hors ligne

```bash
gunzip -c kmp-rtc-server_1.0.0.tar.gz | docker load
cp .env.example .env
```

Dans `.env` :

```dotenv
KMP_RTC_TAG=1.0.0
PUBLIC_HOSTNAME=rtc.local          # ou l'IP du serveur
EXTERNAL_IP=192.168.10.5           # IP du serveur sur le reseau local
TURN_ENABLED=true                  # STUN/TURN local (pas de STUN externe)
```

Dans `docker-compose.yml`, remplacez la ligne `image:` par `image: kmp-rtc-server:${KMP_RTC_TAG:-1.0.0}` et supprimez le bloc `build:`. Puis :

```bash
docker compose -f docker-compose.yml -f docker-compose.host.yml up -d --no-build
```

### 3. Ce qui marche hors ligne

| Fonction | Hors ligne |
|---|---|
| Comptes, connexion, API | Oui |
| Appels audio/vidéo, conférences (réseau local) | Oui |
| Enregistrements, CDR, statistiques | Oui |
| Documentation (`/docs/`) et admin (`/admin/`) | Oui (aucune police ni script externe) |
| Notification d'appel entrant (WebSocket `/ws`) | Oui |
| Certificat Let's Encrypt | Non : utilisez le certificat auto-signé ou celui de votre autorité interne (`config/certs/`) |
| Notifications push Google/Apple | Non (services externes) |

Certificat interne : avec un certificat auto-signé, les applis doivent faire confiance à ce certificat (Android : `network_security_config.xml` ; iOS : profil installé). Sur un réseau local fermé, HTTP simple est aussi possible pour les tests.

### Mettre à jour hors ligne

Refaites `docker save` sur la machine connectée avec la nouvelle version, copiez, `docker load`, changez `KMP_RTC_TAG`, puis `docker compose up -d`. Les volumes (base, enregistrements, secrets) sont conservés.
