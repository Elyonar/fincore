# Product deployable container image.
#
# Deliberately the same shape as services/ledger, services/notification and
# services/core/app — multi-stage, a jlink-trimmed runtime, non-root — because
# images that differ only by accident are images a reviewer has to read
# separately. Built from the repo root so the Maven reactor is available:
#
#   docker build -f services/product/Dockerfile -t fincore/product .
#
# This service moves no money. Its only outbound call is a read to the ledger,
# on the authoring path, to refuse a fee rule naming an account that does not
# exist. The POM is where a reviewer checks that there is nothing else.

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
# a missing child module is a hard parse error, so adding or removing a module
# in the root POM breaks every image whose COPY list was not updated with it.
# That is how the ledger's image broke once already, and it is why ADR 0020's
# cut had to touch all five of these lists in one go.
COPY libs/auth/pom.xml libs/auth/pom.xml
COPY libs/events/pom.xml libs/events/pom.xml
COPY services/ledger/pom.xml services/ledger/pom.xml
COPY services/core/pom.xml services/core/pom.xml
COPY services/core/organization/pom.xml services/core/organization/pom.xml
COPY services/core/orchestration/pom.xml services/core/orchestration/pom.xml
COPY services/core/admin/pom.xml services/core/admin/pom.xml
COPY services/core/app/pom.xml services/core/app/pom.xml
COPY services/product/pom.xml services/product/pom.xml
COPY services/customer/pom.xml services/customer/pom.xml
COPY services/notification/pom.xml services/notification/pom.xml
COPY services/identity/pom.xml services/identity/pom.xml
RUN chmod +x mvnw && ./mvnw -B -pl services/product -am dependency:go-offline

# Only what this image builds.
COPY libs/auth/src libs/auth/src
COPY services/product/src services/product/src
RUN ./mvnw -B -pl services/product -am clean package -DskipTests \
 && cp services/product/target/product-*.jar /build/product.jar

# ---------- custom runtime ----------
FROM eclipse-temurin:25-jdk-alpine AS jre-build

# Spring resolves a great deal reflectively, so the module set is stated
# explicitly rather than inferred by jdeps — jdeps cannot see through a Spring
# Boot fat jar and would produce a runtime that fails at startup instead of at
# build time. Kept identical to the others: the same framework, and lists that
# drift for no reason are lists to debug.
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
# Under an orchestrator, prefer an external probe against
# /actuator/health/readiness and drop this package.
RUN apk add --no-cache curl

ENV JAVA_HOME=/opt/java
ENV PATH="$JAVA_HOME/bin:$PATH"
COPY --from=jre-build /javaruntime "$JAVA_HOME"

# It holds a tenant's pricing, which is commercially sensitive even though it is
# not PII. No reason to be root, and no reason to own the files it executes.
RUN addgroup -S -g 1001 fincore \
 && adduser -S -u 1001 -G fincore -h /app fincore
WORKDIR /app

COPY --from=build --chown=root:root /build/product.jar /app/product.jar

USER fincore
EXPOSE 8084

# MaxRAMPercentage rather than a fixed heap: the container limit is the truth,
# and it changes between a laptop and a production node.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/product.jar"]
