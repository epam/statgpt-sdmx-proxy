############################
# Builder stage
############################
FROM amazoncorretto:25-alpine AS builder

RUN apk add --no-cache bash

COPY . /src
WORKDIR /src

RUN ./gradlew --no-daemon :sdmx-proxy:bootJar -x test

############################
# Runtime stage
############################
FROM amazoncorretto:25-alpine

RUN apk update && apk upgrade && rm -rf /var/cache/apk/*
RUN addgroup -S appgroup && adduser -S appuser -G appgroup -H

RUN mkdir -p /opt/app/sdmx-proxy
WORKDIR /opt/app/sdmx-proxy

COPY --from=builder /src/sdmx-proxy/build/libs/sdmx-proxy-*.jar ./sdmx-proxy.jar
RUN chown -R appuser:appgroup /opt/app/sdmx-proxy

USER appuser

EXPOSE 8050

ENTRYPOINT ["/bin/sh", "-c", "java ${JAVA_OPTS} ${DEBUG_OPTS} ${COMMON_JAVA_OPTS} -jar sdmx-proxy.jar"]
