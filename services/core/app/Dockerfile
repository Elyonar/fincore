# Core deployable container image.
#
# Deliberately the same shape as services/ledger/Dockerfile — multi-stage, a
# jlink-trimmed runtime, non-root — because two money-path images that differ
# only by accident are two images a reviewer has to read separately. Built from
# the repo root so the Maven reactor is available:
#
#   docker build -f services/core/app/Dockerfile -t fincore/core .
#
# Core is one deployable holding five modules (ADR 0006). It ships as one image
# and one process; the module boundary is enforced by database roles inside it,
# not by separate containers.

# ---------- build ----------
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /build

# The Maven wrapper downloads its own distribution and needs a fetch tool that
# the Alpine JDK image does not ship.
RUN apk add --no-cache curl

# Wrapper and POMs first: dependency resolution is cached unless a POM changes,
# so ordinary source edits do not re-download the world. Core needs libs/auth's
# POM too — it is a reactor dependency, not a released artifact.
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
COPY services/core/orchestration/pom.xml services/core/orchestration/pom.xml
COPY services/core/admin/pom.xml services/core/admin/pom.xml
COPY services/core/app/pom.xml services/core/app/pom.xml
COPY services/notification/pom.xml services/notification/pom.xml
COPY services/identity/pom.xml services/identity/pom.xml
RUN chmod +x mvnw && ./mvnw -B -pl services/core/app -am dependency:go-offline

COPY libs/auth/src libs/auth/src
COPY libs/events/src libs/events/src
COPY services/core/customer/src services/core/customer/src
COPY services/core/product/src services/core/product/src
COPY services/core/organization/src services/core/organization/src
COPY services/core/orchestration/src services/core/orchestration/src
COPY services/core/admin/src services/core/admin/src
COPY services/core/app/src services/core/app/src
RUN ./mvnw -B -pl services/core/app -am clean package -DskipTests \
 && cp services/core/app/target/app-*.jar /build/core.jar

# ---------- custom runtime ----------
FROM eclipse-temurin:25-jdk-alpine AS jre-build

# Spring resolves a great deal reflectively, so the module set is stated
# explicitly rather than inferred by jdeps — jdeps cannot see through a Spring
# Boot fat jar and would produce a runtime that fails at startup instead of at
# build time. Anything added here should be justified by an actual failure.
# Kept identical to the ledger's set: Core runs the same framework, and two
# lists that drift for no reason are two lists to debug.
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
# Same trade as the ledger, and the same advice: under an orchestrator prefer an
# external probe against /actuator/health/readiness and drop this package.
RUN apk add --no-cache curl

ENV JAVA_HOME=/opt/java
ENV PATH="$JAVA_HOME/bin:$PATH"
COPY --from=jre-build /javaruntime "$JAVA_HOME"

# Core instructs the ledger to move money. It has no reason to be root, and no
# reason to own the files it executes.
RUN addgroup -S -g 1001 fincore \
 && adduser -S -u 1001 -G fincore -h /app fincore
WORKDIR /app

COPY --from=build --chown=root:root /build/core.jar /app/core.jar

USER fincore
EXPOSE 8081

# MaxRAMPercentage rather than a fixed heap: the container limit is the truth,
# and it changes between a laptop and a production node.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/core.jar"]
