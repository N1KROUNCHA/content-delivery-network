FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY target/demo-client-1.0.0.jar app.jar
EXPOSE 8086
ENTRYPOINT ["java", "-jar", "app.jar"]
