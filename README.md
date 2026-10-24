# kmp-rtc-server

Une seule image Docker pour les **appels audio, appels vidéo, conférences et enregistrements** de vos applications **Kotlin Multiplatform** (Android, iOS, Desktop, Web).

| Service | Rôle |
|---|---|
| **Asterisk 20** | SIP, médias RTP/SRTP, conférences, enregistrement audio. Codecs Opus, G.711, G.722, H.264, VP8 |
| **Ktor** | API REST : comptes, comptes SIP, salles, journal d'appels, enregistrements |
| **MariaDB** | Base de données (journal des appels, statistiques) |
| **Nginx** | HTTPS, WebSocket, documentation, page d'administration |
| **coturn** | STUN / TURN pour passer les box et les pare-feu (NAT) |
| **Supervisor** | Lance et relance automatiquement tous les services |

100 % open source. Fonctionne **sans Internet** une fois installée.

---

## 1. Récupérer l'image

```bash
# GitHub Container Registry
docker pull ghcr.io/nibienvenu/kmp-rtc-server:latest
# ou Docker Hub
docker pull nibienvenu/kmp-rtc-server:latest
```

Pour utiliser l'image Docker Hub avec Compose, mettez dans `.env` :
`KMP_RTC_IMAGE=nibienvenu/kmp-rtc-server`

> Si l'image est privée, connectez-vous d'abord avec un jeton GitHub *classic* qui a le droit `read:packages` :
> `echo "ghp_VOTRE_JETON" | docker login ghcr.io -u VOTRE_UTILISATEUR --password-stdin`

## 2. Récupérer les fichiers de démarrage

Seuls trois fichiers sont nécessaires sur le serveur :

```bash
mkdir kmp-rtc && cd kmp-rtc
BASE=https://raw.githubusercontent.com/NiBienvenu/kmp-rtc-server/main
curl -fsSLO $BASE/docker-compose.yml
curl -fsSLO $BASE/docker-compose.host.yml
curl -fsSL  $BASE/.env.example -o .env
mkdir -p config/certs config/asterisk
```

## 3. Configurer

Ouvrez `.env` et changez au minimum :

```dotenv
PUBLIC_HOSTNAME=rtc.example.com     # nom DNS du serveur (ou "localhost" pour un test)
EXTERNAL_IP=203.0.113.10            # IP que les telephones utilisent pour joindre le serveur
ADMIN_EMAIL=admin@example.com       # compte administrateur cree au premier demarrage
ADMIN_PASSWORD=Un-Mot-De-Passe-Long
```

> **Important :** `EXTERNAL_IP` doit être l'IP que les téléphones utilisent (IP publique du VPS, ou IP du PC sur le réseau local pour un test). Sinon les appels sonnent, mais **sans son**.

Les mots de passe internes (base de données, JWT, TURN) sont créés automatiquement au premier démarrage.

## 4. Démarrer

**Linux (recommandé en production) :**

```bash
docker compose -f docker-compose.yml -f docker-compose.host.yml up -d
```

**Windows / macOS (Docker Desktop) :** mettez d'abord `RTP_END=10200` dans `.env`, puis :

```bash
docker compose up -d
```

Vérifier que tout tourne (le statut doit passer à `healthy` en moins d'une minute) :

```bash
docker compose ps
curl http://localhost/health/ready
```

## 5. Ouvrir les ports

Dans le pare-feu du serveur **et** dans celui de votre hébergeur :

| Port | Protocole | Usage |
|---|---|---|
| 80, 443 | TCP | API, documentation, administration |
| 5060 | UDP + TCP | SIP |
| 10000-20000 | UDP | Audio et vidéo (RTP) |
| 3478 | UDP + TCP | STUN / TURN |
| 49160-49200 | UDP | Relais TURN |

## 6. La documentation complète est dans le conteneur

Une fois le serveur démarré, ouvrez dans un navigateur :

| Adresse | Contenu |
|---|---|
| **`http://<votre-serveur>/docs/`** | **Documentation complète** : déploiement Ubuntu/Windows/macOS, DNS, TLS, NAT, sécurité, API REST, intégration Kotlin Multiplatform, enregistrements, dépannage |
| `http://<votre-serveur>/admin/` | Administration : statistiques, salles, journal d'appels, état d'Asterisk |
| `http://<votre-serveur>/health/ready` | État de l'API, de la base et d'Asterisk |

(Avec `localhost` pour un test sur votre PC : http://localhost/docs/)

La même documentation se trouve aussi dans le dossier [`docs/`](docs/index.md) de ce dépôt.

## 7. Premier test

1. Créez un compte depuis votre application (ou avec l'API, voir `/docs/` > API REST).
2. Depuis l'application, appelez le **600** : c'est un test d'écho, vous devez entendre votre voix.
3. Appelez un autre compte : le son doit passer dans les deux sens.
4. L'appel apparaît dans `/admin/`, et son enregistrement dans `GET /recordings`.

## Commandes utiles

```bash
docker compose logs -f                                    # journaux de tous les services
docker exec kmp-rtc-server supervisorctl status           # etat de chaque service
docker exec -it kmp-rtc-server asterisk -rvvv             # console Asterisk
docker exec kmp-rtc-server asterisk -rx "pjsip show contacts"   # telephones connectes
docker compose pull && docker compose up -d               # mise a jour (donnees conservees)
```

## Construire l'image soi-même

```bash
git clone https://github.com/NiBienvenu/kmp-rtc-server.git
cd kmp-rtc-server
docker compose build          # ou : ./publish.sh 1.0.1  (publie sur ghcr.io)
```

| Dossier | Contenu |
|---|---|
| `ktor-server/` | API REST (projet Gradle autonome) |
| `docker/` | Configs Asterisk, Nginx, MariaDB, coturn, Supervisor, scripts |
| `docs/` | Documentation (convertie en HTML dans l'image) |
| `config/` | Vos certificats TLS et surcharges (monté en lecture seule) |
