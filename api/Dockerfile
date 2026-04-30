# Java runtime image for running the shaded JAR
FROM eclipse-temurin:21.0.10_7-jre-jammy

WORKDIR /app

# Copy the pre-built fat JAR from build context
COPY flux-api.jar /app/flux-api.jar

# Set timezone to UTC
ENV TZ=UTC

# Create non-root user for security
RUN useradd -r -s /bin/sh appuser
USER appuser

EXPOSE 8080

# Run the JAR
CMD ["java", "-jar", "/app/flux-api.jar"]
