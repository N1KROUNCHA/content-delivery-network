FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY target/download-service-1.0.0.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
