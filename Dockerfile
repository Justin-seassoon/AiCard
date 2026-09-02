FROM eclipse-temurin:17-jre

ENV TZ=Asia/Tokyo
WORKDIR /app

COPY aicard-0.1.0-SNAPSHOT.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
