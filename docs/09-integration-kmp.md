# Intégration Kotlin Multiplatform

Ajoutez les appels audio, les appels vidéo et les conférences à votre application Kotlin Multiplatform. Cette page s'appuie sur **kmp-rtc-sample**, une petite application exemple en Material Design 3 que vous pouvez lancer tout de suite, puis copier dans votre projet.

:::: cards
::: card
**Comptes**

Inscription et connexion. Le serveur crée un numéro SIP pour chaque compte.
:::
::: card
**Appels audio et vidéo**

Appelez n'importe quel utilisateur relié au même serveur.
:::
::: card
**Conférences**

Créez une salle, partagez son numéro, tout le monde la rejoint.
:::
::: card
**Historique**

Les appels passés, reçus et manqués, lus depuis le serveur.
:::
::::

## Tester l'exemple en 5 minutes

Il vous faut : un kmp-rtc-server qui tourne, Android Studio, et **deux téléphones Android** (ou un téléphone et un émulateur) sur le même réseau que le serveur.

::: steps
### Démarrez le serveur

```bash
cd kmp-rtc-server
cp .env.example .env      # EXTERNAL_IP = IP LAN de votre PC
docker compose up -d
curl http://localhost/health/ready
```

### Clonez l'application exemple

```bash
git clone https://github.com/NiBienvenu/kmp-rtc-sample.git
cd kmp-rtc-sample
```

### Indiquez l'adresse du serveur

C'est le **seul** réglage. Ouvrez `composeApp/src/commonMain/kotlin/org/kmprtc/sample/Config.kt` :

```kotlin
const val SERVER_HOST = "192.168.1.20"   // IP LAN du PC qui fait tourner Docker
const val API_BASE_URL = "http://$SERVER_HOST"
```

> Émulateur Android sur le même PC que Docker : utilisez `"10.0.2.2"`.
> En production : `"rtc.example.com"` et `https://` dans `API_BASE_URL`.

### Installez l'application sur les deux téléphones

```bash
./gradlew :composeApp:installDebug
```

Acceptez le micro et la caméra au premier lancement.

### Créez un compte sur chaque téléphone

Onglet **Inscription** : nom, email, mot de passe. La pastille en haut passe à **En ligne** quand le téléphone est enregistré sur Asterisk.

### Appelez

Dans **Contacts**, touchez l'icône téléphone (audio) ou caméra (vidéo) à côté de l'autre utilisateur. Seul ? Utilisez la carte **Test d'écho (600)**.
:::

Toutes les copies de l'application qui ont le **même `SERVER_HOST`** voient les mêmes utilisateurs et peuvent s'appeler entre elles.

## Comment l'exemple est organisé

```text
kmp-rtc-sample/composeApp/src/
├── commonMain/kotlin/org/kmprtc/sample/
│   ├── Config.kt             SERVER_HOST (le seul reglage)
│   ├── App.kt                Choix de l'ecran : connexion, accueil ou appel
│   ├── AppViewModel.kt       Tout le parcours : login, SIP, appels, salles
│   ├── data/
│   │   ├── Models.kt         DTO de l'API (@Serializable)
│   │   └── RtcApi.kt         Client Ktor de l'API kmp-rtc-server
│   ├── call/
│   │   ├── CallEngine.kt     Interface commune du moteur d'appel
│   │   └── VideoViews.kt     RemoteVideo() et LocalPreview() (expect)
│   └── ui/                   Ecrans Material 3 (Auth, Home, Call)
├── androidMain/              PjsipCallEngine (PJSIP) + surfaces video
└── jvmMain/                  Desktop : interface complete, moteur vide
```

Règle simple : **l'interface ne parle qu'à `CallEngine`**. Chaque plateforme fournit son moteur. Vous pouvez remplacer PJSIP par Linphone SDK sans toucher aux écrans.

## Intégrer dans votre application

Suivez ces étapes dans votre propre projet. Chaque bloc vient de l'exemple : copiez les fichiers indiqués.

::: steps
### Ajoutez les dépendances

`gradle/libs.versions.toml` :

```toml
[versions]
ktor = "3.3.3"
pjdroid = "2.2.4"

[libraries]
ktor-client-core = { module = "io.ktor:ktor-client-core", version.ref = "ktor" }
ktor-client-okhttp = { module = "io.ktor:ktor-client-okhttp", version.ref = "ktor" }
ktor-client-content-negotiation = { module = "io.ktor:ktor-client-content-negotiation", version.ref = "ktor" }
ktor-serialization-kotlinx-json = { module = "io.ktor:ktor-serialization-kotlinx-json", version.ref = "ktor" }
pjdroid = { module = "com.pjdroid:pjdroid", version.ref = "pjdroid" }
```

`composeApp/build.gradle.kts` :

```kotlin
sourceSets {
    commonMain.dependencies {
        implementation(libs.ktor.client.core)
        implementation(libs.ktor.client.content.negotiation)
        implementation(libs.ktor.serialization.kotlinx.json)
    }
    androidMain.dependencies {
        implementation(libs.ktor.client.okhttp)
        implementation(libs.pjdroid)   // moteur SIP audio + video
    }
}
```

### Déclarez les permissions Android

`AndroidManifest.xml` :

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />

<!-- Seulement si le serveur est en http (test local) -->
<application android:usesCleartextTraffic="true" ... >
```

Demandez `RECORD_AUDIO` et `CAMERA` avant le premier appel (voir `MainActivity.kt`).

### Copiez le client API

Copiez `data/Models.kt` et `data/RtcApi.kt`. Toute l'API utile tient dans une classe :

```kotlin
class RtcApi(private val client: HttpClient = createHttpClient()) {
    suspend fun register(req: RegisterRequest) = send<User> { client.post("$baseUrl/auth/register") { json(req) } }
    suspend fun login(req: LoginRequest): ApiResponse<AuthData>      // garde le jeton JWT
    suspend fun users() = send<List<User>> { client.get("$baseUrl/users") { auth() } }
    suspend fun sipAccount() = send<SipAccount> { client.get("$baseUrl/sip/account") { auth() } }
    suspend fun sipPeer(userId: String) = send<SipPeer> { client.get("$baseUrl/sip/peer/$userId") { auth() } }
    suspend fun rooms() = send<List<Room>> { client.get("$baseUrl/rooms") { auth() } }
    suspend fun createRoom(req: CreateRoomRequest) = send<Room> { client.post("$baseUrl/rooms") { auth(); json(req) } }
    suspend fun history(limit: Int = 50) = send<List<CallRecord>> { client.get("$baseUrl/calls/history?limit=$limit") { auth() } }
}
```

Chaque méthode renvoie `ApiResponse` avec `success = false` et un `message` en cas d'erreur. Pas d'exception à gérer dans l'interface.

### Copiez le moteur d'appel

Copiez le dossier `call/` (commun) et `androidMain/.../call/` (PJSIP). L'interface commune :

```kotlin
interface CallEngine {
    val registration: StateFlow<Registration>   // OFFLINE, CONNECTING, ONLINE, FAILED
    val call: StateFlow<ActiveCall?>            // null = pas d'appel

    suspend fun start(account: SipAccount)      // REGISTER sur Asterisk
    suspend fun dial(sipUri: String, video: Boolean)
    suspend fun answer(video: Boolean)
    suspend fun hangup()
    suspend fun setMuted(muted: Boolean)
    suspend fun setSpeaker(on: Boolean)
    suspend fun setCamera(on: Boolean)
    suspend fun sendDtmf(digits: String)        // PIN des salles
}
```

### Connectez le compte SIP après le login

Le serveur crée le compte SIP à l'inscription. Après la connexion, récupérez-le et démarrez le moteur :

```kotlin
val me = api.login(LoginRequest(email, password)).data?.user ?: return
val sip = api.sipAccount().data ?: return
engine.start(sip)   // la pastille passe a "En ligne"
```

Les identifiants TURN expirent après 24 h. Rappelez `/sip/account` **à chaque lancement** de l'application.

### Appelez un utilisateur

L'annuaire vient de `GET /users`. Pour appeler quelqu'un, demandez son numéro SIP :

```kotlin
val peer = api.sipPeer(user.id).data ?: return
engine.dial("sip:${peer.username}@${sip.domain}", video = true)
```

### Répondez aux appels entrants

Le moteur met `call` à jour. Affichez l'écran d'appel dès qu'il n'est plus `null` :

```kotlin
val call by vm.engine.call.collectAsStateWithLifecycle()

when {
    call != null -> CallScreen(call!!, vm)      // entrant, sortant ou en cours
    me == null -> AuthScreen(state, vm)
    else -> HomeScreen(state, vm)
}

// Dans CallScreen, pour un appel entrant :
if (call.incoming && call.state == CallState.RINGING) {
    Button(onClick = { vm.answer(video = call.video) }) { Text("Repondre") }
}
```

### Affichez la vidéo

Deux composables suffisent. Sur Android, ce sont des `SurfaceView` que PJSIP remplit :

```kotlin
if (call.video && call.state == CallState.CONNECTED) {
    RemoteVideo(Modifier.fillMaxSize())
    LocalPreview(Modifier.align(Alignment.TopEnd).size(110.dp, 146.dp))
}
```

### Ajoutez les conférences

Créez une salle, puis composez son numéro comme un appel normal :

```kotlin
val room = api.createRoom(CreateRoomRequest(name = "Equipe", pin = "1234", video = true)).data ?: return
engine.dial("sip:${room.roomNumber}@${sip.domain}", video = true)
// Avec un PIN, Asterisk le demande : engine.sendDtmf("1234#")
```

Dans la salle : `*1` coupe ou rétablit le micro, `*0` quitte.
:::

## Le parcours d'un appel

```text
App                         kmp-rtc-server
 |-- POST /auth/login -------> Ktor : jeton JWT
 |-- GET /sip/account -------> Ktor : identifiants SIP + STUN/TURN
 |-- REGISTER sip:<domaine> -> Asterisk (5060)
 |-- GET /sip/peer/{userId} -> Ktor : numero SIP du contact
 |-- INVITE sip:<numero>@<domaine> -> Asterisk -> Dial(PJSIP/<numero>)
 |<========== RTP/SRTP 10000-20000 (via Asterisk) ==========>
 |-- BYE ---------------------> Asterisk -> CDR dans MariaDB
 |-- GET /calls/history -----> Ktor lit le CDR
```

## Numéros spéciaux

| Numéro | Effet |
|---|---|
| `600` | Test d'écho (audio et vidéo) |
| `8XXXX` | Salle de conférence (créée par `POST /rooms`) |
| `<username>` | Un utilisateur (7 chiffres) |

## Réglages PJSIP alignés sur le serveur

Déjà faits dans `PjsipCallEngine.kt`. À garder si vous écrivez votre propre moteur :

```kotlin
// Memes codecs que le serveur, dans le meme ordre : pas de transcodage
ep.codecSetPriority("opus/48000/2", 255)
ep.codecSetPriority("G722/16000/1", 200)
ep.codecSetPriority("PCMU/8000/1", 150)
ep.videoCodecSetPriority("H264/97", 255)

// Port local choisi par le systeme
TransportConfig().apply { port = 0 }

// NAT : ICE + STUN + TURN temporaire
natConfig.iceEnabled = true
natConfig.turnEnabled = true
natConfig.turnServer = acc.turn
natConfig.turnUserName = acc.turnUsername
natConfig.turnPassword = acc.turnPassword

// SRTP optionnel (SDES), comme media_encryption=sdes cote Asterisk
mediaConfig.srtpUse = pjmedia_srtp_use.PJMEDIA_SRTP_OPTIONAL

// Video : camera envoyee et video recue affichees automatiquement
videoConfig.autoTransmitOutgoing = true
videoConfig.autoShowIncoming = true
```

Deux règles PJSIP à ne pas oublier :

- **Brancher le son** dans `onCallMediaState` : `audio.startTransmit(playback)` et `capture.startTransmit(audio)`. Sans cela, l'appel est muet.
- **Un seul thread** pour appeler PJSIP. L'exemple passe tout par `pj { ... }`, un thread dédié.

## Plateformes

| Plateforme | Moteur | État dans l'exemple |
|---|---|---|
| Android | PJSIP (pjdroid) | `PjsipCallEngine` : audio, vidéo, conférences |
| Desktop (JVM) | PJSUA2 Java ou Linphone SDK | Interface complète, `NoopCallEngine` (pas de son) |
| iOS | PJSIP xcframework ou Linphone SDK + CallKit | À ajouter : même `CallEngine` |
| Web | JsSIP ou SIP.js | Voir plus bas |

## Appels quand l'application est en arrière-plan

- **Application ouverte** : PJSIP reçoit l'INVITE et l'écran d'appel s'affiche. C'est le cas de l'exemple.
- **Arrière-plan** : `POST /calls/invite` envoie `{"type":"incoming_call",...}` sur le WebSocket `wss://<serveur>/ws`. Tout reste sur votre serveur, sans Google ni Apple.
  - Android : gardez `/ws` et l'enregistrement SIP dans un **service au premier plan** (notification « Prêt à recevoir des appels »).
  - iOS : sans Internet, Apple ne peut pas réveiller une application fermée. Sur un réseau fermé, l'application doit rester ouverte.

## Client Web (optionnel)

Avec JsSIP ou SIP.js. Hors ligne, servez le fichier JS depuis votre serveur, pas depuis un CDN.

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

Un appel vers un utilisateur sonne à la fois dans son application et dans son navigateur.

## Enregistrements

```kotlin
// Liste
val recs = client.get("$API_BASE_URL/recordings") { header(HttpHeaders.Authorization, "Bearer $token") }

// Envoyer une video captee pendant un appel
client.submitFormWithBinaryData(
    url = "$API_BASE_URL/recordings/upload",
    formData = formData {
        append("kind", "video")
        append("file", bytes, Headers.build {
            append(HttpHeaders.ContentType, "video/mp4")
            append(HttpHeaders.ContentDisposition, "filename=\"appel.mp4\"")
        })
    },
) { header(HttpHeaders.Authorization, "Bearer $token") }
```

Détails : [Enregistrements](11-enregistrements.md).

## Checklist

- [ ] `SERVER_HOST` pointe sur Nginx (port 80 ou 443, pas 8080).
- [ ] `EXTERNAL_IP` dans `.env` = l'IP que le téléphone utilise pour joindre le serveur.
- [ ] Ports `5060/udp` et `10000-20000/udp` ouverts.
- [ ] La pastille de l'application affiche **En ligne**.
- [ ] `docker exec kmp-rtc-server asterisk -rx "pjsip show contacts"` liste le téléphone.
- [ ] Appel au `600` : vous vous entendez.
- [ ] Appel entre deux comptes : son et image dans les deux sens.
