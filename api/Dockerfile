# Lightweight Alpine runtime for native binary
FROM debian:bookworm-slim

# Install essential runtime libraries
RUN apk --no-cache add ca-certificates tzdata
RUN apt-get update && apt-get install -y --no-install-recommends zlib1g && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy the pre-built native binary from build context
# The binary should be built in GitHub Actions before Docker build
COPY flux-api /app/flux-api

# Make binary executable
RUN chmod +x /app/flux-api

# Set timezone to UTC
ENV TZ=UTC

# Create non-root user for security
RUN adduser -D -s /bin/sh appuser
USER appuser

EXPOSE 8080

# Run the native binary
CMD ["/app/flux-api"]