FROM amazoncorretto:25-alpine
RUN apk update && apk upgrade && rm -rf /var/cache/apk
RUN addgroup -S appgroup && adduser -S appuser -G appgroup -H

RUN mkdir -p /opt/epam/sdmx-proxy
COPY . /opt/epam/sdmx-proxy
WORKDIR /opt/epam/sdmx-proxy
RUN chown -R appuser:appgroup /opt/epam/sdmx-proxy

USER appuser

EXPOSE 8050
ENTRYPOINT ["/bin/sh", "-c", "java ${JAVA_OPTS} ${DEBUG_OPTS} ${COMMON_JAVA_OPTS} -jar sdmx-proxy-*.jar"]
