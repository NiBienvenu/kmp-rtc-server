# Publication sur GitHub Container Registry (GHCR)

L'image est publiée sous `ghcr.io/<organisation>/kmp-rtc-server`.

## Publication automatique (GitHub Actions)

Le workflow `.github/workflows/kmp-rtc-server.yml` :

1. lance les tests du serveur Ktor ;
2. construit l'image pour `linux/amd64` et `linux/arm64` ;
3. la pousse sur GHCR (sauf pour les pull requests).

| Événement | Tags publiés |
|---|---|
| push sur `main` | `latest`, `sha-abc1234` |
| tag `v1.2.3` | `1.2.3`, `1.2`, `sha-abc1234` |
| pull request | aucun (build seulement) |

Aucun secret à créer : le workflow utilise `GITHUB_TOKEN` avec la permission `packages: write`.

Publier une version :

```bash
git tag v1.0.0
git push origin v1.0.0
```

Après le premier push, dans GitHub : **Profil ou organisation > Packages > kmp-rtc-server > Package settings** :

- **Change visibility** : `Public` pour un `docker pull` sans connexion ;
- **Manage Actions access** : lier le dépôt si besoin.

## Publication manuelle

```bash
# 1. Jeton GitHub (classic) avec les droits write:packages (et read:packages)
export GHCR_TOKEN=ghp_xxx
echo "$GHCR_TOKEN" | docker login ghcr.io -u <utilisateur> --password-stdin

# 2. Construire
docker build -t ghcr.io/<organisation>/kmp-rtc-server:1.0.0 \
             -t ghcr.io/<organisation>/kmp-rtc-server:latest \
             --build-arg VERSION=1.0.0 --build-arg REVISION=$(git rev-parse HEAD) .

# 3. Pousser
docker push ghcr.io/<organisation>/kmp-rtc-server:1.0.0
docker push ghcr.io/<organisation>/kmp-rtc-server:latest
```

Multi-architecture (nécessite Buildx) :

```bash
docker buildx create --use --name kmp
docker buildx build --platform linux/amd64,linux/arm64 \
  -t ghcr.io/<organisation>/kmp-rtc-server:latest --push .
```

## Récupération

```bash
docker pull ghcr.io/<organisation>/kmp-rtc-server:latest
```

Image privée : faites `docker login ghcr.io` avant (jeton avec `read:packages`).

Dans `.env` : `GHCR_OWNER=<organisation>` et `KMP_RTC_TAG=1.0.0` (conseillé en production : une version fixe plutôt que `latest`).

## Taille de l'image

Le build multi-étapes ne garde que le nécessaire :

- pas de JDK ni de Gradle (seulement un JRE réduit par `jlink`) ;
- pas de documentation système, pas de fichiers d'exemple Asterisk ;
- paquets installés avec `--no-install-recommends`.

Vérifier : `docker images ghcr.io/<organisation>/kmp-rtc-server`.
