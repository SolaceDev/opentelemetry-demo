# Copyright The OpenTelemetry Authors
# SPDX-License-Identifier: Apache-2.0


FROM gradle:8-jdk17 AS builder

WORKDIR /usr/src/app/

COPY ./src/frauddetectionservice/ ./
COPY ./pb/ ./src/main/proto/
RUN gradle shadowJar

# -----------------------------------------------------------------------------

FROM gcr.io/distroless/java17-debian11

ARG version=1.31.0
WORKDIR /usr/src/app/

COPY --from=builder /usr/src/app/build/libs/frauddetectionservice-1.0-all.jar ./
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v$version/opentelemetry-javaagent.jar /app/opentelemetry-javaagent.jar
ADD https://repo1.maven.org/maven2/com/solace/solace-opentelemetry-jms-integration/1.1.0/solace-opentelemetry-jms-integration-1.1.0.jar /app/opentelemetry-solace-java.jar
# RUN chmod 644 /app/opentelemetry-javaagent.jar
ENV JAVA_TOOL_OPTIONS="-javaagent:/app/opentelemetry-javaagent.jar \
-Dotel.javaagent.extensions=/app/opentelemetry-solace-java.jar \
-Dotel.propagators=solace_jms_tracecontext \
-Dotel.instrumentation.jms.enabled=true \
-Dotel.traces.exporter=otlp"

ENTRYPOINT [ "java", "-jar", "frauddetectionservice-1.0-all.jar" ]
