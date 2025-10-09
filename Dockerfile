# ---- BUILD ----
FROM gradle:8.7-jdk17 AS build
WORKDIR /app
COPY . .
RUN gradle shadowJar --no-daemon

# ---- RUN ----
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar

# A Render automatikus PORT környezeti változóját használjuk
EXPOSE 10000
CMD ["java", "-jar", "app.jar"]
