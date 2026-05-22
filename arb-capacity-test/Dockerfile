FROM gradle:8.13-jdk21-alpine AS build
WORKDIR /app
COPY . .
RUN gradle :arb-capacity-test:shadowJar --no-daemon -q

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/arb-capacity-test/build/libs/*-all.jar app.jar
ENV AERON_CLIENT_MODE=true
ENTRYPOINT ["java", \
  "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED", \
  "--add-opens=java.base/java.nio=ALL-UNNAMED", \
  "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED", \
  "-Dagrona.disable.bounds.checks=true", \
  "-jar", "app.jar"]
CMD ["--phase", "B", "--mode", "flood", "--duration", "30"]
