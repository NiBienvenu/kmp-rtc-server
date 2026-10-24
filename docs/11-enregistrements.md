# Enregistrements audio et vidéo

Les enregistrements sont stockés dans le volume `recordings` (`/var/spool/kmp-rtc/recordings`).

```text
recordings/
├── calls/AAAA/MM/JJ/<uniqueid>.wav       appels 1-a-1 (Asterisk, automatique)
├── conferences/<salle>/<date-heure>.wav  conférences (Asterisk, automatique)
└── uploads/AAAA/MM/JJ/<uuid>.<ext>       fichiers audio/vidéo envoyés par les applis
```

## Ce qui est enregistré, et par qui

| Quoi | Par qui | Comment | Réglage |
|---|---|---|---|
| Audio des appels | Asterisk (serveur) | `MixMonitor` : les deux voix mélangées, dès que l'appel est décroché | `RECORD_CALLS=true` |
| Audio des conférences | Asterisk (serveur) | ConfBridge : le mélange de tous les participants | `RECORD_CONFERENCES=true` |
| **Vidéo** des appels et conférences | L'application (client) | L'app enregistre localement puis envoie le fichier (`POST /recordings/upload`) | côté app |
| Toute autre capture audio/vidéo | L'application | Même envoi | côté app |

### Pourquoi la vidéo est envoyée par l'application

Asterisk sait enregistrer l'audio d'un appel, mais **pas la vidéo** d'un appel ou d'une conférence (MixMonitor et ConfBridge n'écrivent que l'audio). Les seules façons fiables, en open source, sont :

1. **L'application enregistre** ce qu'elle affiche (image de l'autre + la sienne + le son) et envoie le fichier au serveur. C'est ce que prévoit l'API : simple, marche hors ligne, bonne qualité.
2. Un serveur média dédié (Janus, rtpengine...) placé devant Asterisk. Plus lourd ; à envisager plus tard si l'enregistrement vidéo **côté serveur** devient obligatoire.

Le lien entre une vidéo envoyée et l'appel se fait avec `callUniqueId` (le `uniqueid` du CDR) ou `roomNumber` pour une conférence.

## API

| Méthode | Route | Description |
|---|---|---|
| GET | `/recordings` | Liste ce que l'utilisateur peut voir |
| GET | `/recordings/{id}/file` | Lecture/téléchargement (en-tête `Range` supporté) |
| POST | `/recordings/upload` | Envoi : `file` + `kind=audio\|video` + `callUniqueId?` + `roomNumber?` |
| DELETE | `/recordings/{id}` | Supprime un fichier envoyé |

Droits :

- **appel** : visible par l'appelant et l'appelé ;
- **conférence** : visible par le propriétaire de la salle ;
- **fichier envoyé** : visible par celui qui l'a envoyé ;
- l'**admin** voit tout.

Formats acceptés à l'envoi : `wav`, `ogg`, `opus`, `mp3`, `m4a`, `webm`, `mp4`, `mkv` (500 Mo max par défaut).

```bash
# Lister
curl -s $API/recordings -H "Authorization: Bearer $TOKEN" | jq

# Envoyer une video liee a un appel
curl -s -X POST $API/recordings/upload -H "Authorization: Bearer $TOKEN" \
  -F kind=video -F callUniqueId=1767225600.42 -F file=@appel.mp4

# Ecouter / telecharger
curl -s -H "Authorization: Bearer $TOKEN" -o rec.wav "$API/recordings/call-123/file"
```

## Espace disque

| Format | Taille approximative |
|---|---|
| `wav` (16 bits, 8 kHz) | ~1 Mo par minute |
| `wav49` (GSM) | ~100 Ko par minute |
| Vidéo 720p (envoyée par l'app) | 20 à 60 Mo par minute |

Exemple : 100 appels de 5 min par jour en `wav` = ~500 Mo/jour, ~15 Go/mois.

Suppression automatique : `RECORDINGS_RETENTION_DAYS=90` (enregistrements Asterisk). Les fichiers envoyés se suppriment par l'API.

Sauvegarde :

```bash
docker run --rm -v kmp-rtc_recordings:/data -v "$PWD":/backup ubuntu:24.04 \
  tar czf /backup/recordings_$(date +%F).tar.gz -C /data .
```

## Respect de la vie privée

Prévenez toujours les personnes enregistrées (message dans l'app, ou message vocal au début de l'appel) et respectez la loi de votre pays. Pour couper l'enregistrement : `RECORD_CALLS=false` et/ou `RECORD_CONFERENCES=false`, puis `docker compose up -d`.
