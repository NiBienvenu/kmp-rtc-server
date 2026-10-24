# =============================================================================
# kmp-rtc-server
# Image unique: Asterisk 20 (SIP/RTP/SRTP, conferences, enregistrements) + Ktor (API REST)
#               + MariaDB (CDR) + Nginx (reverse proxy, TLS, docs) + coturn (STUN/TURN), pilotes par Supervisor.
# 100 % open source. Le build a besoin d'Internet; l'image, une fois construite, fonctionne hors ligne.
#
#   docker build -t ghcr.io/<organisation>/kmp-rtc-server:latest .
#
# Etapes:
#   1. ktor-build : compile l'API (ktor-server/) + JRE minimal (jlink)
#   2. docs-build : site React (docs-site/) qui affiche docs/*.md (servi par Nginx sur /docs/)
#   3. runtime    : Ubuntu 24.04 + paquets systeme, sans outils de build
# =============================================================================

ARG UBUNTU_VERSION=24.04

# ----------------------------------------------------------------------------- 1. Ktor
FROM eclipse-temurin:21-jdk-noble AS ktor-build
WORKDIR /src

# Fichiers Gradle d'abord: les dependances sont mises en cache tant qu'ils ne changent pas
COPY ktor-server/gradlew ktor-server/settings.gradle.kts ktor-server/build.gradle.kts ktor-server/gradle.properties ./
COPY ktor-server/gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon --console=plain dependencies > /dev/null
COPY ktor-server/src src
RUN ./gradlew --no-daemon --console=plain installDist -x test

# JRE minimal: seulement les modules Java utilises par l'API (~60 Mo au lieu de ~200 Mo)
RUN set -eux; \
    cd build/install/kmp-rtc-ktor-server/lib; \
    MODS="$(jdeps --ignore-missing-deps -q --multi-release 21 --print-module-deps --class-path '*' *.jar)"; \
    jlink --add-modules "${MODS},jdk.crypto.ec,jdk.naming.dns,jdk.zipfs,jdk.management,jdk.unsupported" \
      --strip-debug --no-man-pages --no-header-files --compress=zip-6 --output /opt/jre; \
    /opt/jre/bin/java -version

# ----------------------------------------------------------------------------- 2. Docs
FROM node:22-alpine AS docs-build
WORKDIR /src
COPY docs-site/package.json docs-site/package-lock.json docs-site/
RUN cd docs-site && npm ci --no-audit --no-fund
COPY docs/ docs/
COPY docs-site/ docs-site/
RUN cd docs-site && npm run build && mkdir -p /out && cp -r dist/. /out/

# ----------------------------------------------------------------------------- 3. Runtime
FROM ubuntu:${UBUNTU_VERSION}

ARG VERSION=dev
ARG REVISION=unknown
# "source" lie automatiquement l'image publiee sur ghcr.io a son depot GitHub
# (la page du package affiche alors le README du depot)
LABEL org.opencontainers.image.source="https://github.com/NiBienvenu/kmp-rtc-server" \
      org.opencontainers.image.documentation="https://github.com/NiBienvenu/kmp-rtc-server#readme" \
      org.opencontainers.image.title="kmp-rtc-server" \
      org.opencontainers.image.description="Asterisk + Ktor + MariaDB + Nginx + coturn for Kotlin Multiplatform real-time apps" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}" \
      org.opencontainers.image.licenses="GPL-2.0-only AND Apache-2.0"

ENV DEBIAN_FRONTEND=noninteractive \
    LANG=C.UTF-8 \
    TZ=UTC \
    JAVA_HOME=/opt/kmp-rtc/jre \
    PATH=/opt/kmp-rtc/jre/bin:/opt/kmp-rtc/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

# Paquets runtime seulement (--no-install-recommends), puis nettoyage
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
      asterisk asterisk-modules asterisk-core-sounds-en-gsm \
      mariadb-server odbc-mariadb unixodbc \
      nginx supervisor coturn \
      gettext-base openssl ca-certificates curl iproute2 tzdata; \
    apt-get clean; \
    # NB: garder /usr/share/asterisk/documentation (Asterisk 20 en a besoin pour demarrer)
    rm -rf /var/lib/apt/lists/* /usr/share/doc/* /usr/share/man/* /usr/share/info/* \
           /usr/share/asterisk/static-http \
           /etc/asterisk/* /etc/nginx/sites-enabled/* /etc/nginx/conf.d/* \
           /var/lib/mysql/* /etc/mysql/mariadb.conf.d/50-server.cnf; \
    # Utilisateur dedie a Ktor, membre du groupe asterisk (socket CLI + fichier des comptes SIP)
    groupadd --system --gid 10001 ktor; \
    useradd --system --uid 10001 --gid ktor --groups asterisk --home-dir /opt/kmp-rtc/ktor --shell /usr/sbin/nologin ktor; \
    mkdir -p /opt/kmp-rtc/bin /var/spool/kmp-rtc/recordings /var/lib/kmp-rtc /var/log/kmp-rtc /etc/kmp-rtc /usr/share/kmp-rtc

COPY --from=ktor-build /opt/jre /opt/kmp-rtc/jre
COPY --from=ktor-build /src/build/install/kmp-rtc-ktor-server /opt/kmp-rtc/ktor
COPY --from=docs-build /out /usr/share/kmp-rtc/docs

COPY docker/asterisk   /opt/kmp-rtc/templates/asterisk
COPY docker/nginx      /opt/kmp-rtc/templates/nginx
COPY docker/coturn     /opt/kmp-rtc/templates/coturn
COPY docker/mariadb    /opt/kmp-rtc/templates/mariadb
COPY docker/admin      /usr/share/kmp-rtc/admin
COPY docker/supervisor/supervisord.conf /etc/supervisor/kmp-rtc.conf
COPY docker/scripts/ /opt/kmp-rtc/bin/

RUN set -eux; \
    chown -R root:root /opt/kmp-rtc; \
    chmod -R go-w /opt/kmp-rtc; \
    chmod 0755 /opt/kmp-rtc/ktor/bin/kmp-rtc-ktor-server /opt/kmp-rtc/bin/*.sh

# Ports (a publier dans docker-compose.yml ou via network_mode: host)
#   80/443 tcp      HTTP/HTTPS (API, WebSocket, docs, admin, SIP WebSocket)
#   5060 udp/tcp    SIP
#   5061 tcp        SIP/TLS (si SIP_TLS_ENABLED=true)
#   3478 udp/tcp    STUN/TURN (coturn)
#   10000-20000 udp RTP/SRTP (audio/video)
#   49160-49200 udp relais TURN
EXPOSE 80/tcp 443/tcp 5060/udp 5060/tcp 5061/tcp 3478/udp 3478/tcp 10000-20000/udp 49160-49200/udp

# Donnees persistantes (les volumes nommes du docker-compose.yml les remplacent)
VOLUME ["/var/lib/mysql", "/var/lib/kmp-rtc", "/var/spool/kmp-rtc/recordings"]

HEALTHCHECK --interval=30s --timeout=10s --start-period=90s --retries=3 \
  CMD ["/opt/kmp-rtc/bin/healthcheck.sh"]

STOPSIGNAL SIGTERM
ENTRYPOINT ["/opt/kmp-rtc/bin/entrypoint.sh"]
CMD ["/usr/bin/supervisord", "-c", "/etc/supervisor/kmp-rtc.conf"]
