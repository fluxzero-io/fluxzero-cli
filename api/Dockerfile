# Minimal runtime container for static native binary
# Using static linking eliminates need for shared libraries
FROM gcr.io/distroless/static-debian12:latest

WORKDIR /app

# Copy the pre-built native binary from build context
# The binary should be built in GitHub Actions before Docker build
COPY flux-api /app/flux-api

# Set timezone to UTC
ENV TZ=UTC

# Use nonroot user for security
USER nonroot

EXPOSE 8080

# Run the native binary
CMD ["./flux-api"]