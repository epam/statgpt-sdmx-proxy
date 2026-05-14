############################
# Builder stage
############################
FROM amazoncorretto:25-alpine AS builder

RUN apk add --no-cache bash

COPY . /src
WORKDIR /src

RUN ./gradlew --no-daemon :sdmx-proxy-config-server:bootJar -x test

############################
# Runtime stage
############################
FROM amazoncorretto:25-alpine

RUN apk update && apk upgrade && rm -rf /var/cache/apk/*
RUN addgroup -S appgroup && adduser -S appuser -G appgroup -H

RUN mkdir -p /opt/app/sdmx-proxy-config-server
WORKDIR /opt/app/sdmx-proxy-config-server

COPY --from=builder /src/sdmx-proxy-config-server/build/libs/sdmx-proxy-config-server-*.jar ./sdmx-proxy-config-server.jar
RUN chown -R appuser:appgroup /opt/app/sdmx-proxy-config-server

USER appuser

EXPOSE 8060

ENTRYPOINT ["/bin/sh", "-c", "java ${JAVA_OPTS} ${DEBUG_OPTS} ${COMMON_JAVA_OPTS} -jar sdmx-proxy-config-server.jar"]
