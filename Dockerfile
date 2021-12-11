FROM hseeberger/scala-sbt:17.0.1_1.5.6_2.13.7 as builder
COPY build.sbt /app/build.sbt
COPY project /app/project
WORKDIR /app
RUN sbt update test:update
COPY . .
RUN sbt compile test stage && \
    chmod -R u=rX,g=rX /app/target/universal/stage && \
    chmod u+x,g+x /app/target/universal/stage/bin/mqtt-prometheus-message-exporter

FROM eclipse-temurin:17-alpine
USER root
RUN apk add --update bash && \
    rm -rf /var/cache/apk/* && \
    adduser -S -u 1001 runnerusr
USER 1001
EXPOSE 9000
ENTRYPOINT ["/app/bin/mqtt-prometheus-message-exporter"]
CMD []
COPY --from=builder --chown=1001:root /app/target/universal/stage /app
