# Sécurité

## Ce que fait l'image

| Sujet | Mesure |
|---|---|
| Utilisateurs | Ktor tourne en `ktor` (uid 10001), Asterisk en `asterisk`, MariaDB en `mysql`, coturn en `turnserver`, les workers Nginx en `www-data`. Seuls Supervisor et le maître Nginx (ports 80/443) restent root. |
| Surface réseau | Ktor, MariaDB et le HTTP d'Asterisk écoutent sur `127.0.0.1` seulement. AMI et ARI sont désactivés. `chan_sip`, IAX2 et autres pilotes inutiles ne sont pas chargés. |
| Secrets | Générés aléatoirement (`openssl rand`), gardés en `0600` dans le volume. Support de `*_FILE` (Docker secrets). Le fichier d'env de Ktor est lisible par `ktor` seulement. Les secrets sont retirés de l'environnement de Supervisor. |
| MariaDB | `root` accessible seulement par socket Unix (pas de mot de passe réseau). Utilisateurs anonymes supprimés. Compte Asterisk limité à `INSERT/SELECT` sur `cdr` et `SELECT` sur `conference_rooms`. `local-infile` désactivé. |
| API | JWT signé HMAC-256. Logout = jeton mis en liste noire. Le rôle envoyé à l'inscription est **ignoré** (personne ne peut s'inscrire admin). Routes admin vérifiées par rôle. |
| Nginx | Limite de débit sur `/auth` (anti brute-force) et sur l'API. `/metrics` limité aux réseaux privés. En-têtes `X-Content-Type-Options`, `X-Frame-Options`, HSTS en HTTPS. `server_tokens off`. TLS 1.2/1.3 seulement. |
| SIP | Pas d'endpoint anonyme. Mots de passe SIP aléatoires (36 caractères hex). SRTP proposé (SDES) ; WebRTC en DTLS-SRTP. Journal `security.log`. |
| TURN | Identifiants temporaires signés, pas de relais vers les réseaux privés, quotas. |
| Webhooks | Signature HMAC-SHA256 obligatoire, comparée en temps constant. |
| Image | Pas d'outils de build, pas de secrets. 100 % open source, aucun appel vers un service externe au fonctionnement. |
| Enregistrements | Accès réservé aux participants de l'appel, au propriétaire de la salle et à l'admin. Chemins vérifiés (impossible de sortir du dossier). Formats d'envoi limités (wav, ogg, mp3, m4a, webm, mp4, mkv). |

## À faire en production

1. **Certificat valide** dans `config/certs/` puis `HTTP_REDIRECT_HTTPS=true`.
2. **SIP/TLS** : `SIP_TLS_ENABLED=true`, `SIP_TRANSPORT=tls`. Sans TLS, les clés SRTP (SDES) circulent en clair dans le SIP.
3. `CORS_ALLOWED_HOSTS` = vos domaines (pas `*`).
4. `chmod 600 .env`, ne jamais le committer.
5. Pare-feu : SSH limité à vos IP. Ne publiez pas d'autres ports.
6. **Fail2ban sur l'hôte**, qui lit `security.log` d'Asterisk (volume `asterisk-logs`) :
   ```ini
   # /etc/fail2ban/jail.d/asterisk.conf
   [asterisk]
   enabled  = true
   filter   = asterisk
   action   = iptables-allports[name=asterisk, protocol=all]
   logpath  = /var/lib/docker/volumes/kmp-rtc_asterisk-logs/_data/security.log
   maxretry = 5
   bantime  = 3600
   ```
   (En mode bridge, ajoutez `chain = DOCKER-USER` à l'action.)
7. Mises à jour régulières : `docker compose pull && docker compose up -d`.
8. Sauvegardes chiffrées de `mariadb-data` et `kmp-data`.
9. Changez le port SIP (`SIP_PORT=5080` par exemple) pour réduire les scans automatiques. Ce n'est pas une vraie protection, mais cela réduit beaucoup le bruit.

## Points d'attention de l'application Omb-Connect

- `Omb-Connect_app/server/src/main/resources/firebase-adminsdk.json` est **dans le dépôt Git** de l'application. C'est une clé privée Google : **révoquez-la** dans la console Firebase et retirez-la du dépôt (et de l'historique). L'image n'utilise plus Firebase.
- Les enregistrements sont des données personnelles : informez les utilisateurs (et respectez la loi locale) avant d'enregistrer. Sauvegardez le volume `recordings` de façon chiffrée.
- La liste noire des jetons (logout) est en mémoire : elle est vidée au redémarrage. Pour la garder, stockez-la en base ou utilisez des jetons courts + refresh token.
- Les mots de passe SIP sont en clair dans `sip_accounts` (nécessaire pour `auth_type=userpass`). Protégez l'accès à la base, ou passez à `md5_cred` plus tard.
- `GET /users` renvoie tous les utilisateurs à tout utilisateur connecté : limitez si l'annuaire ne doit pas être public.
