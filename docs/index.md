# kmp-rtc-server

**kmp-rtc-server** est une image Docker unique qui contient tout ce qu'il faut pour les appels audio, les appels vidéo, les visioconférences et leurs enregistrements, pour une application **Kotlin Multiplatform** (Android, iOS, Desktop, Web).

Tout est **open source** et l'image fonctionne **sans Internet** (serveur local) une fois installée.

```bash
docker pull ghcr.io/<organisation>/kmp-rtc-server:latest
```

## Ce que contient l'image

| Service | Rôle | Port(s) |
|---|---|---|
| **Asterisk 20** | Signalisation SIP, médias RTP/SRTP, conférences (ConfBridge), enregistrement audio, codecs Opus, G.711, G.722, H.264, VP8 | 5060 udp/tcp, 5061 tcp, 10000-20000 udp |
| **Ktor** (API REST) | Comptes, comptes SIP, salles, jetons JWT et TURN, CDR, enregistrements audio/vidéo, webhooks, monitoring | interne 8080 (via Nginx) |
| **MariaDB 10.11** | CDR, journaux, statistiques, données de l'API | interne 3307 (127.0.0.1 seulement, pour ne pas gêner un MySQL déjà installé sur la machine) |
| **Nginx** | Reverse proxy, TLS, WebSocket, cette documentation, interface admin | 80, 443 |
| **coturn** | STUN + TURN pour la traversée NAT | 3478 udp/tcp, 49160-49200 udp |
| **Supervisor** | Lance, surveille et redémarre tous les services | |

## Sommaire

1. [Architecture et arborescence](01-architecture.md)
2. [Configuration : variables, volumes, ports](02-configuration.md)
3. [Déploiement sur Ubuntu (VPS) de A à Z](03-deploiement-ubuntu.md)
4. [Déploiement sur Windows et macOS](04-deploiement-windows-macos.md)
5. [Publication sur GitHub Container Registry](05-ghcr.md)
6. [Réseau : SIP, RTP, NAT, STUN, TURN, ICE](06-reseau-sip-rtp-nat.md)
7. [Sécurité](07-securite.md)
8. [API REST](08-api-rest.md)
9. [Intégration Kotlin Multiplatform](09-integration-kmp.md)
10. [Dépannage](10-depannage.md)
11. [Enregistrements audio et vidéo](11-enregistrements.md)
12. [Licences open source et utilisation hors ligne](12-licences-hors-ligne.md)

## Démarrage en 1 minute (test local)

```bash
cd kmp-rtc-server
cp .env.example .env
# Dans .env : EXTERNAL_IP=<IP LAN de votre PC>, PUBLIC_HOSTNAME=localhost
#             ADMIN_EMAIL=admin@example.com  ADMIN_PASSWORD=ChangeMe123
docker compose up -d
docker compose logs -f
```

Puis ouvrir :

- `http://localhost/docs/` : cette documentation
- `http://localhost/admin/` : interface d'administration
- `http://localhost/health/ready` : état de l'API, de la base et d'Asterisk

Pour tester l'audio sans second téléphone, appelez le **600** depuis l'application : c'est un test d'écho (votre voix et votre vidéo reviennent vers vous).
