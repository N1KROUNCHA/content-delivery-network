FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY target/content-service-1.0.0.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
