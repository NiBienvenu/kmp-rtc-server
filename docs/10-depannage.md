# Dépannage

## Commandes utiles

```bash
docker compose ps                                        # etat + healthcheck
docker compose logs -f                                   # tous les services
docker exec kmp-rtc-server supervisorctl status          # chaque service
docker exec kmp-rtc-server supervisorctl restart ktor    # relancer un service
docker exec -it kmp-rtc-server asterisk -rvvv            # console Asterisk en direct
docker exec kmp-rtc-server tail -f /var/log/asterisk/messages.log
docker exec kmp-rtc-server tail -f /var/log/asterisk/security.log
docker exec -it kmp-rtc-server mariadb kmp_rtc           # SQL (root via socket)
```

Commandes Asterisk :

```text
pjsip show transports        ; ports et NAT
pjsip show endpoints         ; comptes
pjsip show contacts          ; telephones enregistres (Avail = OK)
pjsip set logger on          ; affiche tous les messages SIP
rtp set debug on             ; affiche les paquets RTP
core show channels           ; appels en cours
confbridge list              ; conferences
odbc show                    ; connexion MariaDB pour les CDR
cdr show status
```

## Le téléphone ne s'enregistre pas

1. `pjsip set logger on` puis relancer l'app : voyez-vous le `REGISTER` ?
   - **Non** : réseau. Vérifiez `SERVER_HOST`, le port 5060/udp dans le pare-feu, et que le téléphone est sur le bon réseau.
   - **Oui, réponse 401 en boucle** : mauvais mot de passe. Vérifiez que le compte existe : `pjsip show endpoint <username>`. Sinon, redémarrez Ktor (il resynchronise) : `supervisorctl restart ktor`.
   - **Oui, 403/404** : nom inconnu. Regardez `/var/lib/kmp-rtc/asterisk/pjsip_users.conf`.
2. Domaine : l'app doit utiliser `domain` renvoyé par `/sip/account` (pas une URL avec `http://`).

## L'appel sonne mais pas de son (ou son dans un seul sens)

C'est presque toujours le NAT ou le pare-feu RTP.

1. `EXTERNAL_IP` est-il défini et correct ? `pjsip show transports` doit montrer `External Media Address`.
2. Les ports 10000-20000/udp sont-ils ouverts (VPS **et** pare-feu du fournisseur) ?
3. La plage publiée par Docker est-elle la même que `RTP_START`/`RTP_END` ?
4. `rtp set debug on` pendant un appel :
   - `Got RTP packet from` absent : le RTP du téléphone n'arrive pas (pare-feu, IP).
   - `Sent RTP packet to 172.x.x.x` : l'adresse du téléphone est privée, vérifiez `rewrite_contact`/`rtp_symmetric` (déjà actifs) et le STUN côté app.
5. Testez le **600** : si l'écho marche, le chemin téléphone <-> serveur est bon ; le problème est chez l'autre téléphone.
6. En LAN avec Docker Desktop : `EXTERNAL_IP` = IP LAN du PC, pas `localhost`.

## Pas de vidéo

- Les deux clients doivent partager un codec vidéo (H.264 ou VP8) : Asterisk ne transcode pas la vidéo.
- `core show channel <canal>` montre les formats négociés.
- La vidéo demande plus de débit : vérifiez les pertes avec `rtp set debug on` ou `pjsip show channelstats`.

## Les CDR n'apparaissent pas

- `odbc show` doit afficher `kmp-mariadb` connecté. Sinon : `docker exec kmp-rtc-server isql -v kmp-mariadb asterisk_cdr <mot de passe>` (dans `/var/lib/kmp-rtc/secrets/CDR_DB_PASSWORD`).
- Les CDR sont écrits par lots toutes les 5 s (`cdr.conf`). `cdr show status` montre la file.

## Conférence : "salle invalide"

- La salle doit exister et être active : `GET /rooms`. Le numéro est `8XXXX`.
- Vérifiez en SQL : `docker exec kmp-rtc-server mariadb kmp_rtc -e "SELECT room_number, active FROM conference_rooms"`.
- `odbc show` doit montrer la connexion `kmp-mariadb` (func_odbc l'utilise).

## L'API répond 502 / 503

- `supervisorctl status ktor` et `docker compose logs kmp-rtc-server | grep '\[ktor\]'`.
- `Database ... not ready` : MariaDB démarre encore, attendez ou regardez les logs MariaDB.
- `/health/ready` renvoie l'état de chaque dépendance.

## Démarrage lent

- Docker Desktop + grande plage RTP : réduisez `RTP_END` (voir [Windows/macOS](04-deploiement-windows-macos.md)).
- Sur Linux, utilisez `docker-compose.host.yml`.

## Repartir de zéro (efface toutes les données)

```bash
docker compose down -v
```
