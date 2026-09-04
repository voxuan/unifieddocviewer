# Optimized Dockerfile for Unified Document Viewer
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Run as non-root user for security
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

COPY target/unifieddocviewer-*.jar app.jar

EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=5s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-XX:+UseZGC", "-XX:+ZGenerational", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]

