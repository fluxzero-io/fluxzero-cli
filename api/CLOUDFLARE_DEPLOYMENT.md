# Cloudflare Containers Deployment

This document describes how the Flux API is deployed to Cloudflare Containers (Beta).

## Prerequisites

1. **Cloudflare Account**: You need a Cloudflare account with Workers Paid plan
2. **API Token**: Create a Cloudflare API token with the following permissions:
   - Zone:Zone Settings:Edit
   - Zone:Zone:Read
   - Account:Cloudflare Workers:Edit

## Setup

### 1. GitHub Secrets Configuration

Add the following secret to your GitHub repository:

- `CLOUDFLARE_API_TOKEN`: Your Cloudflare API token

### 2. Update Worker URL

After your first deployment, update the `API_URL` in `.github/workflows/deploy-api.yml`:

```yaml
API_URL="https://flux-api-prod.your-subdomain.workers.dev"
```

Replace `your-subdomain` with your actual Cloudflare subdomain.

## Architecture

The deployment consists of:

1. **Container**: The Kotlin/Ktor API running in a Docker container
2. **Worker**: JavaScript code that manages the container instances
3. **Durable Objects**: Provides container instance management

## Local Development

To test the Cloudflare deployment locally:

```bash
cd api
npm install
npx wrangler dev
```

## Manual Deployment

To deploy manually:

```bash
cd api
./gradlew shadowJar
cp build/libs/flux-api-*.jar build/libs/flux-api.jar
npm install
npx wrangler deploy --env production
```

## Configuration

- **wrangler.toml**: Cloudflare Workers configuration
- **src/worker.js**: Worker code that manages container instances
- **Dockerfile**: Container definition (unchanged)

## Monitoring

After deployment, the API will be available at:
- Production: `https://flux-api-prod.your-subdomain.workers.dev`
- Health Check: `https://flux-api-prod.your-subdomain.workers.dev/health`

## Troubleshooting

1. **Deployment Fails**: Check that your API token has the correct permissions
2. **Container Won't Start**: Ensure the JAR file is built correctly
3. **Health Check Fails**: Verify the container is exposing port 8080

## Beta Limitations

Cloudflare Containers is currently in beta:
- Features may change
- Community support via [Discord](https://discord.cloudflare.com)
- Monitor [Cloudflare Containers docs](https://developers.cloudflare.com/containers/) for updates