# Déploiement sur Windows et macOS

Sur Windows et macOS, Docker tourne dans une petite machine virtuelle (Docker Desktop). Il faut donc **toujours** utiliser le mode bridge (`docker-compose.yml` seul) : le mode `host` n'est pas disponible pour la VoIP.

Ces systèmes conviennent pour **le développement et les tests**. Pour la production, utilisez un serveur Linux (voir [Ubuntu](03-deploiement-ubuntu.md)).

## 1. Installer Docker Desktop

- Windows 10/11 : <https://docs.docker.com/desktop/setup/install/windows-install/> (backend **WSL 2** conseillé).
- macOS (Intel ou Apple Silicon) : <https://docs.docker.com/desktop/setup/install/mac-install/>.

Dans Docker Desktop, **Settings > Resources** : au moins 2 CPU et 4 Go de RAM.

## 2. Trouver l'IP LAN de la machine

Les téléphones de test doivent joindre cette IP.

- Windows (PowerShell) : `ipconfig` puis lire "Adresse IPv4" de la carte Wi-Fi/Ethernet (ex. `192.168.1.20`).
- macOS : `ipconfig getifaddr en0` (Wi-Fi) ou `en1`.

## 3. Réduire la plage RTP

Docker Desktop publie chaque port un par un : 10 000 ports UDP rendent le démarrage très lent. Pour un test, 200 ports suffisent (environ 50 appels) :

```dotenv
PUBLIC_HOSTNAME=localhost
EXTERNAL_IP=192.168.1.20
RTP_START=10000
RTP_END=10200
ADMIN_EMAIL=admin@example.com
ADMIN_PASSWORD=ChangeMe123
```

## 4. Démarrer

Windows (PowerShell) :

```powershell
cd C:\chemin\vers\kmp-rtc-server
Copy-Item .env.example .env   # puis editer .env
docker compose up -d
docker compose logs -f
```

macOS (Terminal) :

```bash
cd ~/chemin/vers/kmp-rtc-server
cp .env.example .env          # puis editer .env
docker compose up -d
docker compose logs -f
```

Sur un Mac Apple Silicon, l'image `linux/arm64` est utilisée automatiquement si elle a été publiée (voir le workflow GHCR). Sinon, construisez-la localement : `docker compose build`.

## 5. Pare-feu

- **Windows** : au premier lancement, autorisez Docker Desktop sur les réseaux **privés**. Si les téléphones ne joignent pas le PC, ajoutez une règle entrante (PowerShell admin) :
  ```powershell
  New-NetFirewallRule -DisplayName "kmp-rtc SIP" -Direction Inbound -Protocol UDP -LocalPort 5060,3478,10000-10200 -Action Allow -Profile Private
  New-NetFirewallRule -DisplayName "kmp-rtc HTTP" -Direction Inbound -Protocol TCP -LocalPort 80,443,5060,3478 -Action Allow -Profile Private
  ```
  Le profil réseau Wi-Fi doit être "Privé".
- **macOS** : Réglages Système > Réseau > Coupe-feu. Autorisez Docker si demandé.

## 6. Tester

- Navigateur : `http://localhost/docs/` et `http://localhost/admin/`.
- Application Android sur le même Wi-Fi : `SERVER_HOST = "192.168.1.20"` (voir [Intégration KMP](09-integration-kmp.md)).
- Émulateur Android sur le même PC : `SERVER_HOST = "10.0.2.2"` pour l'API. Pour le SIP, gardez `EXTERNAL_IP` = IP LAN.

## Limites connues

- Le NAT de Docker Desktop ajoute un peu de latence : normal pour un poste de dev.
- Si le PC change de réseau (autre Wi-Fi), mettez à jour `EXTERNAL_IP` puis `docker compose up -d`.
- Sous WSL 2, l'IP de la VM change à chaque redémarrage : c'est sans effet, car on utilise l'IP LAN de Windows.
