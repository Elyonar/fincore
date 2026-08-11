# Identity deployable container image (ADR 0018).
#
# Deliberately the same shape as the other services' Dockerfiles — multi-stage, a jlink-trimmed
# runtime, non-root — because images that differ only by accident are images a reviewer has to
# read separately. Built from the repo root so the Maven reactor is available:
#
#   docker build -f services/identity/Dockerfile -t fincore/identity .
#
# This service mints tokens and holds credential hashes. It calls no other deployable; the POM is
# where a reviewer checks that the arrow never reverses.

# ---------- build ----------
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /build

RUN apk add --no-cache curl

# Wrapper and POMs first: dependency resolution caches unless a POM changes. Every module POM,
# because Maven parses the whole reactor before building any part of it — a missing child module
# is a hard parse error, which is how the ledger's image broke once.
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
COPY libs/auth/pom.xml libs/auth/pom.xml
COPY libs/events/pom.xml libs/events/pom.xml
COPY services/ledger/pom.xml services/ledger/pom.xml
COPY services/core/pom.xml services/core/pom.xml
COPY services/core/customer/pom.xml services/core/customer/pom.xml
COPY services/core/product/pom.xml services/core/product/pom.xml
COPY services/core/organization/pom.xml services/core/organization/pom.xml
COPY services/core/orchestration/pom.xml services/core/orchestration/pom.xml
COPY services/core/admin/pom.xml services/core/admin/pom.xml
COPY services/core/app/pom.xml services/core/app/pom.xml
COPY services/notification/pom.xml services/notification/pom.xml
COPY services/identity/pom.xml services/identity/pom.xml
RUN chmod +x mvnw && ./mvnw -B -pl services/identity -am dependency:go-offline

# Only what this image builds. libs/auth is here for the reactor and this service's test scope;
# at runtime identity does not import it.
COPY libs/auth/src libs/auth/src
COPY services/identity/src services/identity/src
RUN ./mvnw -B -pl services/identity -am clean package -DskipTests \
 && cp services/identity/target/identity-*.jar /build/identity.jar

# ---------- custom runtime ----------
FROM eclipse-temurin:25-jdk-alpine AS jre-build
# Kept identical to the other services: same framework, and lists that drift for no reason are
# lists to debug. jdk.crypto.* stay because token signing and Argon2 need them.
RUN "$JAVA_HOME/bin/jlink" \
      --add-modules \
        java.base,java.compiler,java.desktop,java.instrument,java.logging,\
java.management,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,\
java.security.jgss,java.security.sasl,java.sql,java.sql.rowset,\
java.transaction.xa,java.xml,java.xml.crypto,jdk.crypto.cryptoki,jdk.crypto.ec,\
jdk.jfr,jdk.management,jdk.unsupported,jdk.httpserver \
      --strip-debug --no-header-files --no-man-pages --compress=zip-6 \
      --output /javaruntime

# ---------- run ----------
FROM alpine:3.21 AS runtime
RUN apk add --no-cache curl

ENV JAVA_HOME=/opt/java
ENV PATH="$JAVA_HOME/bin:$PATH"
COPY --from=jre-build /javaruntime "$JAVA_HOME"

# This service holds credential hashes and signs tokens. It has no reason to be root, and no
# reason to own the files it executes.
RUN addgroup -S -g 1001 fincore \
 && adduser -S -u 1001 -G fincore -h /app fincore
WORKDIR /app

COPY --from=build --chown=root:root /build/identity.jar /app/identity.jar

USER fincore
EXPOSE 8083

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/identity.jar"]
