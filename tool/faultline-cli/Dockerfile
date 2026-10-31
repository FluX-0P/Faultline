# Faultline's CLI as a container, for the fake payment provider in deploy/docker-compose.yml (faultline stub).
# Build the jar first: (cd tool && ./mvnw -q package -DskipTests)
FROM eclipse-temurin:25.0.5_8-jre
WORKDIR /faultline
COPY target/faultline.jar faultline.jar
ENTRYPOINT ["java", "-Duser.timezone=UTC", "-jar", "faultline.jar"]
