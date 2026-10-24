# Déploiement sur Ubuntu (VPS) de A à Z

Exemple : un VPS Ubuntu 24.04 avec l'IP publique `203.0.113.10` et le nom `rtc.example.com`.

Matériel conseillé :

| Usage | vCPU | RAM | Disque |
|---|---|---|---|
| Test, < 20 appels simultanés | 1 | 2 Go | 20 Go |
| Production, ~100 appels audio | 2 | 4 Go | 40 Go |
| Conférences vidéo (transcodage) | 4+ | 8 Go | 80 Go |

L'audio Opus utilise environ 40 à 100 kbit/s par sens et par appel, la vidéo 0,5 à 2 Mbit/s.

## 1. Préparer le serveur

```bash
ssh root@203.0.113.10
apt update && apt -y upgrade
timedatectl set-timezone UTC
# Utilisateur de travail
adduser deploy && usermod -aG sudo deploy
```

## 2. Installer Docker (dépôt officiel, inclut Compose et Buildx)

```bash
sudo apt install -y ca-certificates curl
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo $VERSION_CODENAME) stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list
sudo apt update
sudo apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo usermod -aG docker deploy   # puis se reconnecter
docker compose version
```

> Avec le paquet Ubuntu `docker.io`, installez aussi `docker-buildx` et `docker-compose-v2`.

## 3. DNS

Chez votre fournisseur DNS, créez :

| Type | Nom | Valeur |
|---|---|---|
| A | `rtc` | `203.0.113.10` |
| AAAA | `rtc` | IPv6 du VPS (optionnel) |

Vérifiez : `dig +short rtc.example.com` doit renvoyer `203.0.113.10`.

Option SIP avancée (clients qui utilisent la découverte DNS) :

| Type | Nom | Valeur |
|---|---|---|
| SRV | `_sip._udp.rtc` | `10 10 5060 rtc.example.com` |
| SRV | `_sips._tcp.rtc` | `10 10 5061 rtc.example.com` |

## 4. Pare-feu

Le pare-feu du VPS (UFW) **et** celui du fournisseur (Security Group, Cloud Firewall) doivent autoriser :

```bash
sudo ufw default deny incoming
sudo ufw default allow outgoing
sudo ufw allow 22/tcp            # SSH (limitez a votre IP si possible)
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw allow 5060/udp
sudo ufw allow 5060/tcp
sudo ufw allow 5061/tcp          # si SIP/TLS
sudo ufw allow 10000:20000/udp   # RTP
sudo ufw allow 3478/udp
sudo ufw allow 3478/tcp
sudo ufw allow 49160:49200/udp   # relais TURN
sudo ufw enable
sudo ufw status verbose
```

> **Attention** : en mode bridge, Docker ouvre lui-même les ports publiés dans iptables, **avant** UFW. Les ports du `docker-compose.yml` sont donc accessibles même si UFW ne les liste pas. En mode `host` (recommandé), UFW s'applique normalement.

## 5. Optimisations système (VoIP)

```bash
sudo tee /etc/sysctl.d/99-kmp-rtc.conf <<'EOF'
# Tampons UDP plus grands (RTP, video)
net.core.rmem_max = 26214400
net.core.wmem_max = 26214400
net.core.rmem_default = 1048576
net.core.wmem_default = 1048576
net.core.netdev_max_backlog = 5000
# Connexions nombreuses
net.core.somaxconn = 4096
fs.file-max = 1000000
EOF
sudo sysctl --system
```

## 6. Récupérer le projet et l'image

```bash
sudo mkdir -p /opt/kmp-rtc && sudo chown deploy: /opt/kmp-rtc && cd /opt/kmp-rtc
# Seuls ces fichiers sont utiles sur le serveur:
curl -fsSLO https://raw.githubusercontent.com/<organisation>/<depot>/main/docker-compose.yml
curl -fsSLO https://raw.githubusercontent.com/<organisation>/<depot>/main/docker-compose.host.yml
curl -fsSL  https://raw.githubusercontent.com/<organisation>/<depot>/main/.env.example -o .env
mkdir -p config/certs config/asterisk certbot-www

# Image privee ? Connectez-vous d'abord (jeton GitHub avec read:packages)
echo "$GHCR_TOKEN" | docker login ghcr.io -u <utilisateur> --password-stdin
docker pull ghcr.io/<organisation>/kmp-rtc-server:latest
```

## 7. Configurer `.env`

```dotenv
GHCR_OWNER=<organisation>
PUBLIC_HOSTNAME=rtc.example.com
EXTERNAL_IP=203.0.113.10
SIP_TRANSPORT=udp
ADMIN_EMAIL=admin@example.com
ADMIN_PASSWORD=Un-Mot-De-Passe-Long-2026
CORS_ALLOWED_HOSTS=rtc.example.com
HTTP_REDIRECT_HTTPS=false    # passer a true apres l'etape 9
```

Protégez le fichier : `chmod 600 .env`.

## 8. Démarrer

Mode **host** (recommandé sur Linux : pas de NAT Docker pour le RTP) :

```bash
docker compose -f docker-compose.yml -f docker-compose.host.yml up -d
docker compose logs -f
```

Mode bridge (si vous voulez garder l'isolation réseau Docker) :

```bash
docker compose up -d
```

Vérifications :

```bash
docker compose ps                                   # STATUS doit passer a "healthy"
docker exec kmp-rtc-server supervisorctl status     # tous RUNNING
curl -s http://rtc.example.com/health/ready
docker exec kmp-rtc-server asterisk -rx "pjsip show transports"
```

## 9. TLS avec Let's Encrypt

Nginx sert déjà `/.well-known/acme-challenge/` depuis `./certbot-www`. Sur l'hôte :

```bash
sudo apt install -y certbot
sudo certbot certonly --webroot -w /opt/kmp-rtc/certbot-www -d rtc.example.com \
  --email admin@example.com --agree-tos --no-eff-email

# Copier le certificat la ou le conteneur le lit
sudo cp -L /etc/letsencrypt/live/rtc.example.com/fullchain.pem /opt/kmp-rtc/config/certs/
sudo cp -L /etc/letsencrypt/live/rtc.example.com/privkey.pem   /opt/kmp-rtc/config/certs/
sudo chmod 644 /opt/kmp-rtc/config/certs/*.pem
docker compose restart
```

Renouvellement automatique :

```bash
sudo tee /etc/letsencrypt/renewal-hooks/deploy/kmp-rtc.sh <<'EOF'
#!/bin/sh
cp -L /etc/letsencrypt/live/rtc.example.com/fullchain.pem /opt/kmp-rtc/config/certs/
cp -L /etc/letsencrypt/live/rtc.example.com/privkey.pem   /opt/kmp-rtc/config/certs/
docker restart kmp-rtc-server
EOF
sudo chmod +x /etc/letsencrypt/renewal-hooks/deploy/kmp-rtc.sh
sudo certbot renew --dry-run
```

Ensuite, dans `.env` : `HTTP_REDIRECT_HTTPS=true`, et pour chiffrer aussi la signalisation SIP : `SIP_TLS_ENABLED=true`, `SIP_TRANSPORT=tls`. Puis `docker compose up -d`.

## 10. Test d'appel

1. Créez deux comptes dans l'application (ou avec `curl`, voir [API REST](08-api-rest.md)).
2. Vérifiez qu'ils sont enregistrés :
   ```bash
   docker exec kmp-rtc-server asterisk -rx "pjsip show contacts"
   ```
3. Appelez le **600** (écho) : vous devez vous entendre. Si vous n'entendez rien, voir [Dépannage](10-depannage.md).
4. Appelez l'autre compte.
5. Le CDR apparaît dans `http://rtc.example.com/admin/` après quelques secondes.

## 11. Mettre à jour

```bash
cd /opt/kmp-rtc
docker compose pull
docker compose -f docker-compose.yml -f docker-compose.host.yml up -d
docker image prune -f
```

Les volumes (base, secrets, comptes SIP) sont conservés.

## 12. Construire l'image sur le serveur (sans GHCR)

```bash
git clone https://github.com/<organisation>/<depot>.git /opt/kmp-rtc-src
cd /opt/kmp-rtc-src
docker compose build
docker compose up -d
```

## 13. Service au démarrage

Docker redémarre le conteneur tout seul (`restart: unless-stopped`). Vérifiez que Docker démarre avec le système : `sudo systemctl enable docker`.
