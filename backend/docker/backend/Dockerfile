FROM maven:3.8-eclipse-temurin-8 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn package -DskipTests -B

FROM eclipse-temurin:8-jre
WORKDIR /app
COPY --from=build /build/target/coldchain-compliance.jar /app/app.jar
RUN mkdir -p /data/keys /data/exports
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
