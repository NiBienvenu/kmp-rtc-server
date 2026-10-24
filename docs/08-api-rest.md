# API REST

Base : `https://rtc.example.com` (toutes les routes sont aussi disponibles sous `/api/...`, utile pour un client Web).

Réponse standard :

```json
{ "success": true, "message": "optionnel", "data": { } }
```

Authentification : en-tête `Authorization: Bearer <jeton>` (obtenu par `/auth/login`).

## Endpoints

### Authentification

| Méthode | Route | Auth | Description |
|---|---|---|---|
| POST | `/auth/register` | non | Crée un compte (+ compte SIP). Le champ `role` est ignoré. |
| POST | `/auth/login` | non | Renvoie `{token, user}` |
| POST | `/auth/logout` | JWT | Invalide le jeton |

### Utilisateurs

| Méthode | Route | Auth | Description |
|---|---|---|---|
| GET | `/users` | JWT | Liste |
| GET | `/users/me` | JWT | Profil connecté |
| GET | `/users/{id}` | JWT | Détail |
| PUT | `/users/{id}` | JWT (soi-même ou admin) | Modifier |
| DELETE | `/users/{id}` | admin | Supprimer |

### SIP / temps réel

| Méthode | Route | Auth | Description |
|---|---|---|---|
| GET | `/sip/account` | JWT | Identifiants SIP + STUN/TURN/ICE de l'utilisateur |
| GET | `/sip/peer/{userId}` | JWT | URI SIP d'un autre utilisateur (sans mot de passe) |
| GET | `/rtc/ice-servers` | JWT | Serveurs ICE au format WebRTC |
| POST | `/devices/tokens` | JWT | Enregistre un jeton d'appareil (stocké, pour usage futur) |
| POST | `/calls/invite` | JWT | Notifie le destinataire d'un appel entrant via le WebSocket `/ws` |
| GET | `/ws` | JWT | WebSocket temps réel : reçoit `{"type":"incoming_call",...}` |

### Conférences

| Méthode | Route | Auth | Description |
|---|---|---|---|
| GET | `/rooms` | JWT | Salles actives (PIN visible pour le propriétaire et l'admin) |
| POST | `/rooms` | JWT | Crée une salle `{name, pin?, maxParticipants, video}` |
| GET | `/rooms/{id}` | JWT | Détail |
| DELETE | `/rooms/{id}` | propriétaire ou admin | Ferme la salle (Asterisk refuse ensuite l'accès) |

Pour rejoindre : composer `sip:<roomNumber>@<domaine>` (ex. `81234`). Avec un PIN, Asterisk le demande (saisie DTMF). Menu en conférence : `*1` micro on/off, `*0` quitter.

### Journal d'appels et statistiques

| Méthode | Route | Auth | Description |
|---|---|---|---|
| GET | `/calls/history?limit=50` | JWT | Appels de l'utilisateur (CDR) |
| GET | `/admin/cdr?limit=100` | admin | Derniers CDR |
| GET | `/admin/stats?days=30` | admin | Totaux et stats par jour |
| GET | `/admin/asterisk/{contacts,endpoints,channels,conferences,uptime}` | admin | État Asterisk en direct (liste fixe de commandes) |

### Enregistrements

| Méthode | Route | Auth | Description |
|---|---|---|---|
| GET | `/recordings` | JWT | Liste : appels, conférences (salles dont on est propriétaire), fichiers envoyés |
| GET | `/recordings/{id}/file` | JWT | Lecture / téléchargement (supporte `Range` : lecture vidéo avec avance rapide) |
| POST | `/recordings/upload` | JWT | Envoi multipart : `file`, `kind=audio\|video`, `callUniqueId?`, `roomNumber?` |
| DELETE | `/recordings/{id}` | propriétaire ou admin | Supprime un fichier envoyé (`upload-...`) |

Détails : [Enregistrements](11-enregistrements.md).

### Monitoring et webhooks

| Méthode | Route | Auth | Description |
|---|---|---|---|
| GET | `/health` | non | Vivant |
| GET | `/health/ready` | non | Base + Asterisk prêts (503 sinon) |
| GET | `/metrics` | réseau privé | Format Prometheus |
| POST | `/webhooks/{source}` | signature HMAC | Webhook entrant, stocké dans `webhook_events` |

## Exemples

```bash
API=https://rtc.example.com

# Inscription
curl -s -X POST $API/auth/register -H 'Content-Type: application/json' \
  -d '{"displayName":"Alice","email":"alice@example.com","phone":"+25700000000","password":"secret123"}'

# Connexion
TOKEN=$(curl -s -X POST $API/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"alice@example.com","password":"secret123"}' | jq -r .data.token)

# Compte SIP
curl -s $API/sip/account -H "Authorization: Bearer $TOKEN" | jq
```

Réponse de `/sip/account` :

```json
{
  "success": true,
  "data": {
    "sipUri": "sip:4821937@rtc.example.com",
    "username": "4821937",
    "password": "9f2c...",
    "domain": "rtc.example.com",
    "transport": "udp",
    "stun": "rtc.example.com:3478",
    "turn": "rtc.example.com:3478",
    "turnUsername": "1767225600:7b0e...",
    "turnPassword": "Zk1...",
    "iceEnabled": true,
    "wsUri": "wss://rtc.example.com/asterisk/ws",
    "webUsername": "4821937-web"
  }
}
```

```bash
# Creer une salle
curl -s -X POST $API/rooms -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"Equipe","pin":"1234","maxParticipants":10,"video":true}' | jq

# Historique
curl -s "$API/calls/history?limit=20" -H "Authorization: Bearer $TOKEN" | jq

# Webhook signe
BODY='{"type":"payment.succeeded","id":"evt_1"}'
SIG="sha256=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$WEBHOOK_SECRET" | cut -d' ' -f2)"
curl -s -X POST $API/webhooks/stripe -H "X-Signature: $SIG" -H 'Content-Type: application/json' -d "$BODY"
```

## Ajouter une fonctionnalité

L'API suit la structure existante :

1. **Table** : `models/` (objet Exposed `Table`).
2. **Repository** : `repositories/`.
3. **Service** (logique) : `services/`.
4. **Routes** : `routes/` (fonction d'extension `Route.xxxRoutes(...)`).
5. **Enregistrer** : `plugins/Routing.kt` (+ la table dans `DatabaseFactory.kt`).

Pas besoin de toucher à Asterisk, sauf si la fonction change la façon de composer un numéro (dialplan).
