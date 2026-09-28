FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/ src/
# Normalize the wrapper when building from a Windows checkout.
RUN sed -i 's/\r$//' mvnw
RUN --mount=type=cache,target=/root/.m2 sh ./mvnw --batch-mode --no-transfer-progress -DskipTests package

FROM eclipse-temurin:21-jre-jammy
RUN groupadd --gid 10001 clinicflow && useradd --uid 10001 --gid clinicflow --no-create-home clinicflow
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/target/clinicflow-api-*.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
