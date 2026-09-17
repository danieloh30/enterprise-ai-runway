FROM docker.io/library/eclipse-temurin:25-jre
ARG MODULE
WORKDIR /app
COPY --chown=1001:0 ${MODULE}/target/quarkus-app/lib/ ./lib/
COPY --chown=1001:0 ${MODULE}/target/quarkus-app/*.jar ./
COPY --chown=1001:0 ${MODULE}/target/quarkus-app/app/ ./app/
COPY --chown=1001:0 ${MODULE}/target/quarkus-app/quarkus/ ./quarkus/
USER 1001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -Djava.util.logging.manager=org.jboss.logmanager.LogManager"
ENTRYPOINT ["java", "-jar", "/app/quarkus-run.jar"]
