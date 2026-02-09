# Fly.io Deployment

This module includes a Fly.io deployment using the Dockerfile-based JVM JAR runtime.

## Prerequisites

- Fly.io account and `flyctl` installed locally (optional for local deploys)
- GitHub secret: `FLY_API_TOKEN`
- (Optional) GitHub variable: `FLY_APP_NAME` (defaults to `fluxzero-cli-api` if not set)

## Files

- `api/fly.toml`: Fly app config (HTTP service on port 8080, health check `/api/health`)
- `api/Dockerfile`: Runs shaded JAR using Java 21 JRE
- `.github/workflows/deploy-fly.yml`: CI deployment workflow

## Local Deploy

```bash
# Build shaded JAR
./gradlew :api:shadowJar
cp api/build/libs/flux-api-*.jar api/flux-api.jar

# Deploy (requires flyctl login)
cd api
flyctl deploy --remote-only --now
```

## CI Deploy

The workflow `Deploy API to Fly.io` runs on release completion or manually.
It builds the shaded JAR, prepares `api/flux-api.jar` as Docker context input, and runs `flyctl deploy`.

Configure:

- Add `FLY_API_TOKEN` to GitHub repository Secrets
- Optionally set `FLY_APP_NAME` in GitHub repository Variables if you need a custom app name

