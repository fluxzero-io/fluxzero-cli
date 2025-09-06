FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Copy the fat JAR (build process should copy the versioned JAR to flux-api.jar)
COPY build/libs/flux-api.jar app.jar

# Set timezone to UTC
ENV TZ=UTC

# Create non-root user
RUN addgroup -g 1001 -S appuser && \
    adduser -S -D -H -u 1001 -G appuser appuser && \
    chown appuser:appuser /app

USER appuser

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/api/health || exit 1

EXPOSE 8080

CMD ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]