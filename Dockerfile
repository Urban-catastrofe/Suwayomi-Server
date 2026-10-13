# CEF/Chromium is glibc-only; a musl base (Alpine) cannot load libcef.so.
FROM docker.io/library/eclipse-temurin:21-jre-jammy

ENV DEBIAN_FRONTEND=noninteractive
ENV DBUS_SESSION_BUS_ADDRESS="disabled:"

# CEF runtime deps + Xvfb (JCEF needs an X display); wget for the compose healthcheck
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        tini xvfb xauth wget ca-certificates \
        libglib2.0-0 libnss3 libnspr4 libdbus-1-3 \
        libatk1.0-0 libatk-bridge2.0-0 libcups2 libdrm2 libgbm1 \
        libpango-1.0-0 libcairo2 libasound2 \
        libx11-6 libx11-xcb1 libxcb1 libxcomposite1 libxdamage1 libxext6 \
        libxfixes3 libxrandr2 libxrender1 libxss1 libxtst6 libxcursor1 libxi6 \
        libegl1 libxkbcommon0 fonts-noto-cjk && \
    rm -rf /var/lib/apt/lists/* && \
    mkdir -p /tmp/.X11-unix && chmod 1777 /tmp/.X11-unix

WORKDIR /app

COPY server/build/*.jar /app/suwayomi-server.jar

ENV JAVA_OPTS=""
EXPOSE 4567

# Clear stale CEF singleton locks, fix exec bits, then run on a virtual X display
ENTRYPOINT ["tini", "--", "sh", "-c", "rm -rf /root/.local/share/Tachidesk/cache/kcef/Singleton* 2>/dev/null; chmod -R a+x /root/.local/share/Tachidesk/bin/kcef 2>/dev/null || true; exec xvfb-run --auto-servernum java -Duser.home=/root $JAVA_OPTS -jar /app/suwayomi-server.jar"]
