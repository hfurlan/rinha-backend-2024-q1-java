FROM maven:3.9.6-eclipse-temurin-21-alpine
WORKDIR /app
COPY . .
RUN mvn clean install
CMD ["java", "-jar", "target/rinha-0.0.1-SNAPSHOT.jar"]