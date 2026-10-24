# Réseau : SIP, RTP, NAT, STUN, TURN, ICE

## Les flux

| Flux | Port | Rôle |
|---|---|---|
| SIP | 5060 udp/tcp | Enregistrement (REGISTER), mise en relation (INVITE), fin d'appel (BYE) |
| SIP/TLS | 5061 tcp | Même chose, chiffré |
| SIP WebSocket | 443 `/asterisk/ws` | Navigateurs (WebRTC) |
| RTP/SRTP | 10000-20000 udp | Le son et l'image |
| STUN/TURN | 3478 | Découvrir son IP publique / relayer quand le direct échoue |

La signalisation et les médias passent sur des ports **différents**. Beaucoup de problèmes "l'appel sonne mais pas de son" viennent du RTP et non du SIP.

## Pourquoi le NAT casse l'audio

Asterisk écrit dans le SDP (le message qui décrit les médias) l'IP où il attend le RTP. Dans Docker en mode bridge, cette IP est celle du conteneur (`172.18.x.x`), inconnue des téléphones. Le téléphone envoie alors son audio vers une IP impossible à joindre : **pas de son, ou son dans un seul sens**.

La solution est de dire à Asterisk sa vraie adresse :

```ini
; genere automatiquement si EXTERNAL_IP est defini
[transport-udp]
external_media_address = 203.0.113.10
external_signaling_address = 203.0.113.10
local_net = 172.18.0.0/16
local_net = 127.0.0.0/8
```

- `external_*_address` : l'IP écrite dans les messages SIP/SDP pour les clients "extérieurs".
- `local_net` : les réseaux pour lesquels on **ne** réécrit **pas** l'adresse.

Règle simple : `EXTERNAL_IP` = l'adresse que les téléphones utilisent pour joindre le serveur.

| Situation | EXTERNAL_IP | Mode conseillé |
|---|---|---|
| VPS avec IP publique | IP publique | host |
| VPS derrière un NAT (cloud avec IP privée + IP publique 1:1, AWS, GCP...) | IP publique | host ou bridge |
| Test en LAN, Docker Desktop | IP LAN du PC | bridge |
| Test en LAN, Linux, mode host | vide | host |

## Configuration Asterisk appliquée

### rtp.conf

```ini
[general]
rtpstart = 10000        ; RTP_START
rtpend = 20000          ; RTP_END
strictrtp = yes         ; ignore le RTP d'une source inconnue
probation = 4
icesupport = yes

[ice_host_candidates]   ; ajoute si EXTERNAL_IP est defini
172.18.0.2 => 203.0.113.10
```

La plage RTP doit être identique **à trois endroits** : `rtp.conf` (automatique), la publication Docker (automatique avec Compose), le pare-feu (à faire). Chaque appel utilise 2 ports par flux (RTP + RTCP) : 10 000 ports = environ 2 500 flux.

### Endpoints (template `kmp-endpoint` dans pjsip.conf)

| Option | Valeur | Pourquoi |
|---|---|---|
| `direct_media` | `no` | Le média passe toujours par Asterisk : pas de média direct entre deux NAT. |
| `rtp_symmetric` | `yes` | Asterisk renvoie le RTP là d'où il arrive (traverse la plupart des NAT). |
| `force_rport` | `yes` | Répond au port source réel du client. |
| `rewrite_contact` | `yes` | Garde l'adresse publique du client pour le joindre. |
| `ice_support` | `yes` | Accepte les candidats ICE des clients. |
| `media_encryption` | `sdes` + optimistic | SRTP si le client le propose. |
| `rtp_keepalive` | `15` | Garde le trou NAT ouvert pendant les silences. |
| `rtp_timeout` | `60` | Raccroche si plus aucun média (appel fantôme). |
| `tos_audio` / `tos_video` | `ef` / `af41` | Priorité réseau (DSCP) pour la voix et la vidéo. |
| `qualify_frequency` (AOR) | `30` | OPTIONS toutes les 30 s : détecte les téléphones partis, garde le NAT ouvert. |

## STUN, TURN, ICE

- **STUN** : le client demande "quelle est mon IP publique ?". Il peut ainsi annoncer la bonne adresse.
- **TURN** : si aucun chemin direct ne marche (NAT symétrique, réseau d'entreprise), le média passe par un relais (coturn).
- **ICE** : le client essaie plusieurs chemins (local, STUN, TURN) et garde le meilleur.

L'image contient **coturn**. `GET /sip/account` et `GET /rtc/ice-servers` donnent aux applis :

```json
{
  "stun": "rtc.example.com:3478",
  "turn": "rtc.example.com:3478",
  "turnUsername": "1767225600:4f1c...",
  "turnPassword": "kq3l...=",
  "iceEnabled": true
}
```

Les identifiants TURN sont **temporaires** (24 h par défaut) : `username = "<expiration>:<userId>"`, `password = base64(HMAC-SHA1(TURN_SECRET, username))`. coturn les vérifie avec le même secret (`use-auth-secret`). Aucun mot de passe TURN fixe n'est stocké dans l'application.

coturn refuse de relayer vers les réseaux privés (protection contre les attaques internes), sauf l'IP du conteneur et `EXTERNAL_IP` (pour joindre Asterisk).

## Codecs

| Type | Codecs (ordre de préférence) | Remarques |
|---|---|---|
| Audio | Opus, G.722, G.711 µ-law, G.711 A-law | Opus : meilleure qualité, résiste aux pertes. G.711 : compatibilité maximale. |
| Vidéo | H.264, VP8 | Asterisk ne transcode pas la vidéo : les deux clients doivent avoir un codec commun. |

Asterisk transcode l'audio si besoin (ex. Opus vers G.711) grâce à `codec_opus_open_source`. Le transcodage coûte du CPU : alignez les codecs des applications sur ceux du serveur.

## Optimisations

1. **Mode host** sur Linux : supprime le NAT Docker (moins de latence, pas de `docker-proxy`).
2. **Tampons UDP** plus grands (`net.core.rmem_max`, voir [Ubuntu](03-deploiement-ubuntu.md)).
3. **Opus partout** : pas de transcodage, bonne qualité même avec 5 % de pertes.
4. `ptime` de 20 ms côté client (équilibre latence/débit).
5. Jitter buffer côté client (PJSIP : `jbInit`, `jbMax`), et `jitterbuffer=yes` dans les conférences.
6. **Keepalive** SIP (`keep_alive_interval=30`) et RTP (`rtp_keepalive=15`) : évite que les box ferment les trous NAT.
7. QoS DSCP (EF pour l'audio) : utile si votre réseau ou votre fournisseur respecte DSCP.
8. Conférences vidéo nombreuses : plus de CPU, ou `CONF_VIDEO_MODE=sfu` avec des clients WebRTC.

## Montée en charge

- Un serveur 2 vCPU gère environ 100 à 200 appels audio sans transcodage.
- Chaque appel prend 2 à 4 ports RTP : agrandissez `RTP_END` si besoin.
- Au-delà d'un serveur : plusieurs instances derrière un répartiteur SIP (Kamailio/OpenSIPS) et une base MariaDB externe (`MARIADB_ENABLED=false`, `DB_HOST=...`). Le "contrat" Ktor/Asterisk (fichier + tables) reste le même.
