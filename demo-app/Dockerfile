# The demo app as a container, for Faultline's multi-worker runs (deploy/docker-compose.yml, profile "demo").
# Build the jar first: ./mvnw -q package -DskipTests
FROM eclipse-temurin:25.0.5_8-jre
WORKDIR /app
COPY target/demo-app-0.0.1-SNAPSHOT.jar app.jar
ENTRYPOINT ["java", "-Duser.timezone=UTC", "-jar", "app.jar"]
