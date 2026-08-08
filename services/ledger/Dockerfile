# Ledger Service container image.
#
# Multi-stage and explicit rather than buildpack-generated: this file is part of
# the platform's public argument for how it runs, and a reviewer should be able
# to read it. Built from the repo root so the Maven reactor is available:
#
#   docker build -f services/ledger/Dockerfile -t fincore/ledger .
#
# The runtime is a jlink-trimmed JRE on Alpine rather than a stock JRE image.
# A full JRE ships every module the JDK has; this service uses a fraction of
# them. Smaller image, faster pulls, and — the part that matters for a service
# holding money — a materially smaller attack surface, because a module that
# is not in the image cannot be exploited in it.

# ---------- build ----------
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /build

# The Maven wrapper downloads its own distribution and needs a fetch tool that
# the Alpine JDK image does not ship.
RUN apk add --no-cache curl

# Wrapper and POMs first: dependency resolution is cached unless a POM changes,
# so ordinary source edits do not re-download the world.
COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
# Every module POM, because Maven parses the whole reactor before it builds any
# part of it. `-pl <module> -am` narrows what is *built*, not what must *exist*:
# a missing child module is a hard parse error, so adding a module to the root
# POM breaks every image that does not list it. That is how this broke — libs
# and Core were added, the ledger's image kept copying only its own POM, and
# `docker compose up` failed on a service whose own code had not changed.
COPY libs/auth/pom.xml libs/auth/pom.xml
COPY libs/events/pom.xml libs/events/pom.xml
COPY services/ledger/pom.xml services/ledger/pom.xml
COPY services/core/pom.xml services/core/pom.xml
COPY services/core/customer/pom.xml services/core/customer/pom.xml
COPY services/core/product/pom.xml services/core/product/pom.xml
COPY services/core/organization/pom.xml services/core/organization/pom.xml
COPY services/core/lending/pom.xml services/core/lending/pom.xml
COPY services/core/orchestration/pom.xml services/core/orchestration/pom.xml
COPY services/core/app/pom.xml services/core/app/pom.xml
COPY services/notification/pom.xml services/notification/pom.xml
RUN chmod +x mvnw && ./mvnw -B -pl services/ledger -am dependency:go-offline

# The libraries' sources too. A copied POM is enough for dependency *resolution*; building a
# module from source needs its tree. `auth` was the third time this bit — the ledger gained
# `LedgerAuth` (ADR 0014) and `mvn install` stayed green, because on a developer's disk the module
# is right there. Only the image, which sees nothing it was not handed, failed. Every module whose
# source this build compiles is now asserted by `DockerfileModuleCatalogTest`, so a fourth time
# fails in the suite instead of at `docker compose up`.
COPY libs/auth/src libs/auth/src
COPY libs/events/src libs/events/src
COPY services/ledger/src services/ledger/src
RUN ./mvnw -B -pl services/ledger -am clean package -DskipTests \
 && cp services/ledger/target/ledger-*.jar /build/ledger.jar

# ---------- custom runtime ----------
FROM eclipse-temurin:25-jdk-alpine AS jre-build

# Spring resolves a great deal reflectively, so the module set is stated
# explicitly rather than inferred by jdeps — jdeps cannot see through a Spring
# Boot fat jar and would produce a runtime that fails at startup instead of at
# build time. Anything added here should be justified by an actual failure.
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

# The base image ships no HTTP client, so an in-container healthcheck needs one.
# This is a deliberate trade: curl inside a money-handling image is also a
# ready-made download and exfiltration tool for anyone who achieves RCE. It is
# here because Compose can only probe from inside the container. Under an
# orchestrator, prefer an external probe (Kubernetes `httpGet`) against
# /actuator/health/readiness and drop this package.
RUN apk add --no-cache curl

ENV JAVA_HOME=/opt/java
ENV PATH="$JAVA_HOME/bin:$PATH"
COPY --from=jre-build /javaruntime "$JAVA_HOME"

# The ledger holds the platform's money. It has no reason to be root, and no
# reason to own the files it executes.
RUN addgroup -S -g 1001 fincore \
 && adduser -S -u 1001 -G fincore -h /app fincore
WORKDIR /app

COPY --from=build --chown=root:root /build/ledger.jar /app/ledger.jar

USER fincore
EXPOSE 8080

# MaxRAMPercentage rather than a fixed heap: the container limit is the truth,
# and it changes between a laptop and a production node.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/ledger.jar"]
