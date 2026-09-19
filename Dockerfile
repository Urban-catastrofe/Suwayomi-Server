FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# zstd-kmp ships glibc-linked JNI binaries; gcompat provides Alpine's loader shim.
RUN apk add --no-cache gcompat && mkdir -p /data

# Copy the built jar
COPY server/build/*.jar /app/suwayomi-server.jar

ENV JAVA_OPTS=""

EXPOSE 4567

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/suwayomi-server.jar"]
