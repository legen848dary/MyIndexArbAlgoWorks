FROM gradle:8.13-jdk21-alpine AS build
WORKDIR /app
COPY . .
RUN gradle :arb-execution:shadowJar --no-daemon -q

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/arb-execution/build/libs/*-all.jar app.jar
ENTRYPOINT ["java", \
  "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED", \
  "--add-opens=java.base/java.nio=ALL-UNNAMED", \
  "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED", \
  "-Dagrona.disable.bounds.checks=true", \
  "-jar", "app.jar"]
