FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY target/verification-service-1.0.0.jar app.jar
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "app.jar"]
