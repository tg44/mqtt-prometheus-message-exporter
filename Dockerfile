FROM hseeberger/scala-sbt:8u222_1.3.6_2.13.1 as builder
COPY build.sbt /app/build.sbt
COPY project /app/project
WORKDIR /app
RUN sbt update test:update
COPY . .
RUN sbt formatTest compile test stage


FROM openjdk:8
WORKDIR /app
COPY --from=builder /app/target/universal/stage /app
USER root
RUN useradd --system --create-home --uid 1001 --gid 0 runnerusr && \
    chmod -R u=rX,g=rX /app && \
    chmod u+x,g+x /app/bin/mqtt-prometheus-message-exporter && \
    chown -R 1001:root /app
USER 1001

EXPOSE 9000
ENTRYPOINT ["/app/bin/mqtt-prometheus-message-exporter"]
CMD []
