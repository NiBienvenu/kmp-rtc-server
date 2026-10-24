# Intégration Kotlin Multiplatform

Exemple réel : l'application de test **Omb-Connect** (dossier `Omb-Connect_app/`, à côté de `kmp-rtc-server/`).

## Le parcours d'un appel

```text
App                         kmp-rtc-server
 |-- POST /auth/login -------> Ktor: JWT
 |-- GET /sip/account -------> Ktor: cree le compte SIP en base,
 |                             l'ecrit dans pjsip_users.conf, recharge PJSIP,
 |<-- username, password, domain, transport, stun, turn (+ identifiants temporaires)
 |-- REGISTER sip:<domain> --> Asterisk (5060)
 |-- GET /sip/peer/{userId} -> Ktor: URI SIP du contact
 |-- INVITE sip:<contact>@<domain> -> Asterisk -> Dial(PJSIP/<contact>)
 |<========== RTP/SRTP 10000-20000 (via Asterisk) ==========>
 |-- BYE ---------------------> Asterisk -> CDR dans MariaDB
 |-- GET /calls/history -----> Ktor lit le CDR
```

## 1. Adresse du serveur

`Omb-Connect_app/composeApp/src/commonMain/kotlin/bi/nibienvenu/omb_connect/utils/Constant.kt` :

```kotlin
const val SERVER_HOST = "192.168.56.200"   // IP LAN, ou "10.0.2.2" (emulateur), ou DNS
const val API_BASE_URL = "http://$SERVER_HOST"
// Production : const val API_BASE_URL = "https://rtc.example.com"
```

Toutes les requêtes passent par Nginx (port 80/443). Il n'y a plus de port Ktor à indiquer (l'ancien `:82` a été retiré).

En HTTPS avec un vrai certificat, vous pouvez retirer `android:usesCleartextTraffic="true"` du `AndroidManifest.xml`.

## 2. Récupérer le compte SIP

Déjà fait dans `navigation/AppNavigation.kt` :

```kotlin
val acc = voipRepository.fetchSipAccount().data ?: return
callEngine.configure(
    SipAccountConfig(
        username = acc.username,
        password = acc.password,
        domain = acc.domain,
        transport = when (acc.transport.lowercase()) {
            "udp" -> SipTransport.UDP
            "tcp" -> SipTransport.TCP
            else -> SipTransport.TLS
        },
        stunServer = acc.stun,
        turnServer = acc.turn,
        turnUsername = acc.turnUsername,
        turnPassword = acc.turnPassword,
    )
)
callEngine.register()
```

Les identifiants TURN expirent (24 h par défaut). Rappelez `/sip/account` à chaque lancement de l'app ou à chaque connexion.

## 3. Moteur SIP par plateforme

L'interface commune est `CallEngine` (`features/calls/engine/CallEngine.kt`). Chaque plateforme a son implémentation.

| Plateforme | Bibliothèque conseillée | État dans Omb-Connect |
|---|---|---|
| Android | PJSIP (pjdroid) ou Linphone SDK | `PjsipCallEngineAndroid` (fait) |
| iOS | PJSIP (xcframework) ou Linphone SDK + CallKit/PushKit | `NoopCallEngine` (à faire) |
| Desktop (JVM) | PJSUA2 Java (JNI) ou Linphone SDK Java | `NoopCallEngine` (à faire) |
| Web (Kotlin/JS ou Wasm) | JsSIP ou SIP.js via `wsUri` + `webUsername` | non ciblé |

### Réglages Android (PJSIP) alignés sur le serveur

Dans `PjsipCallEngineAndroid.kt` :

```kotlin
// Codecs dans le meme ordre que le serveur : pas de transcodage
ep.codecSetPriority("opus/48000/2", 255)
ep.codecSetPriority("G722/16000/1", 200)
ep.codecSetPriority("PCMU/8000/1", 150)
ep.videoCodecSetPriority("H264/97", 255)

// Port local 0 : choisi par le systeme (pas de conflit sur le telephone)
tcfg.port = 0

// NAT
nat.iceEnabled = true
nat.contactRewriteUse = 1
nat.sdpNatRewriteUse = 1
nat.udpKaIntervalSec = 15
nat.turnEnabled = true            // si turn fourni
nat.turnServer = cfg.turnServer
nat.turnUserName = cfg.turnUsername
nat.turnPassword = cfg.turnPassword

// SRTP optionnel (SDES) : correspond a media_encryption=sdes + optimistic
acfg.mediaConfig.srtpUse = pjmedia_srtp_use.PJMEDIA_SRTP_OPTIONAL
```

## 4. Composer un appel

```kotlin
val peer = voipRepository.fetchSipPeer(contactUserId).data ?: return
callEngine.startOutgoingCall(target = peer.sipUri, media = CallMedia.VIDEO)
```

Numéros spéciaux :

| Numéro | Effet |
|---|---|
| `600` | Test d'écho (audio + vidéo) |
| `8XXXX` | Salle de conférence (créée par `POST /rooms`) |
| `<username>` | Utilisateur (7 chiffres) |

Les noms SIP sont des nombres de 7 chiffres. Les anciens comptes en hexadécimal (créés avant kmp-rtc-server) marchent encore : le plan de numérotation accepte lettres et chiffres.

## 5. Appels entrants et notifications

- App ouverte : PJSIP reçoit l'INVITE (`onIncomingCall`) et l'UI affiche l'écran d'appel.
- App en arrière-plan : `POST /calls/invite` envoie `{"type":"incoming_call","target":...,"callerName":...,"kind":"voice|video"}` sur le WebSocket `wss://<serveur>/ws` (JWT). Tout reste sur votre serveur, sans Google ni Apple.
  - Android : gardez `/ws` et l'enregistrement SIP ouverts dans un **service au premier plan** (notification permanente "Prêt à recevoir des appels").
  - Desktop : la connexion reste ouverte tant que l'app tourne.
  - iOS : sans Internet, Apple ne peut pas réveiller une app fermée (PushKit passe par les serveurs d'Apple). Sur un réseau local fermé, l'app iOS doit rester ouverte pour recevoir les appels.
- L'ancien service `IncomingCallFirebaseService` de l'app (FCM) ne marche pas hors ligne : utilisez le WebSocket.

## 6. Historique et conférences dans l'app

```kotlin
// Historique (exemple avec le HttpClient Ktor de l'app)
val history: ApiResponse<List<CallRecordDto>> = client.get("$API_BASE_URL/calls/history?limit=50") {
    header(HttpHeaders.Authorization, "Bearer $token")
}.body()

// Conference
val room: ApiResponse<RoomDto> = client.post("$API_BASE_URL/rooms") {
    header(HttpHeaders.Authorization, "Bearer $token")
    contentType(ContentType.Application.Json)
    setBody(CreateRoomDto(name = "Equipe", pin = null, maxParticipants = 10))
}.body()
callEngine.startOutgoingCall("sip:${room.data!!.roomNumber}@${acc.domain}", CallMedia.VIDEO)
```

(DTO à déclarer dans `data/remote/` avec `@Serializable`, mêmes champs que dans [API REST](08-api-rest.md).)

## 7. Client Web (optionnel)

Avec JsSIP ou SIP.js (MIT, open source). Hors ligne : servez le fichier JS depuis votre serveur, pas depuis un CDN.

```javascript
const ice = (await api("/rtc/ice-servers")).iceServers;
const acc = await api("/sip/account");
const ua = new JsSIP.UA({
  sockets: [new JsSIP.WebSocketInterface(acc.wsUri)],
  uri: `sip:${acc.webUsername}@${acc.domain}`,
  password: acc.password,
});
ua.start();
ua.call(`sip:${peer.username}@${acc.domain}`, { pcConfig: { iceServers: ice }, mediaConstraints: { audio: true, video: true } });
```

Un appel vers un utilisateur sonne à la fois sur son app native et sur son navigateur (`Dial(PJSIP/<user>&PJSIP/<user>-web)`).

## 8. Enregistrements dans l'app

```kotlin
// Liste
val recs = client.get("$API_BASE_URL/recordings") { header(HttpHeaders.Authorization, "Bearer $token") }

// Envoyer une video captee pendant un appel (Ktor client, multipart)
client.submitFormWithBinaryData(
    url = "$API_BASE_URL/recordings/upload",
    formData = formData {
        append("kind", "video")
        append("callUniqueId", callUniqueId)          // optionnel
        append("file", bytes, Headers.build {
            append(HttpHeaders.ContentType, "video/mp4")
            append(HttpHeaders.ContentDisposition, "filename=\"appel.mp4\"")
        })
    },
) { header(HttpHeaders.Authorization, "Bearer $token") }
```

Lecture : ouvrir `"$API_BASE_URL" + item.downloadUrl` dans un lecteur (ExoPlayer, AVPlayer, VLCJ) avec l'en-tête `Authorization`.

## Checklist d'intégration

- [ ] `API_BASE_URL` pointe sur Nginx (pas de port 8080 ni 82).
- [ ] `EXTERNAL_IP` = IP que le téléphone utilise pour joindre le serveur.
- [ ] Ports 5060/udp et 10000-20000/udp ouverts.
- [ ] `docker exec kmp-rtc-server asterisk -rx "pjsip show contacts"` montre le téléphone.
- [ ] Appel au `600` : on s'entend.
- [ ] Appel entre deux comptes : son dans les deux sens.
