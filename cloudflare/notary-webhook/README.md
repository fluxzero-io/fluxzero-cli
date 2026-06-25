# Fluxzero notary webhook

Cloudflare Worker that receives Apple notarization webhooks and starts the GitHub Actions workflow that staples and publishes the macOS Launchpad DMG.

The Worker deliberately does not store Apple credentials. It only validates a secret URL path, creates a short-lived GitHub App installation token, and creates a GitHub `repository_dispatch` event for `fluxzero-io/fluxzero-cli`.

## Runtime flow

1. The macOS Launchpad build submits the signed DMG with `notarytool submit --webhook`.
2. Apple calls this Worker when the notarization submission changes.
3. The Worker validates the path token and dispatches `macos-launchpad-notarization-complete` to GitHub.
4. GitHub Actions downloads the pending DMG artifact, asks Apple for the actual notarization status, staples the DMG when accepted, and attaches it to the release.

## GitHub repository secrets

The deploy workflow needs these secrets in `fluxzero-io/fluxzero-cli`:

- `CLOUDFLARE_API_TOKEN`: Cloudflare token allowed to deploy this Worker and set Worker secrets.
- `CLOUDFLARE_ACCOUNT_ID`: Cloudflare account id.
- `NOTARY_WEBHOOK_TOKEN`: random URL token for `/apple-notary/<token>`.
- `NOTARY_WEBHOOK_GITHUB_APP_ID`: GitHub App id.
- `NOTARY_WEBHOOK_GITHUB_APP_INSTALLATION_ID`: installation id for the GitHub App installation on `fluxzero-io/fluxzero-cli`.
- `NOTARY_WEBHOOK_GITHUB_APP_PRIVATE_KEY_BASE64`: base64 encoded GitHub App private key PEM.

The macOS build workflow also needs:

- `APPLE_NOTARY_WEBHOOK_URL`: full Worker callback URL, without query parameters, for example `https://fluxzero-notary-webhook.<workers-subdomain>.workers.dev/apple-notary/<token>`.

Deploy the Worker with the `Deploy Notary Webhook` GitHub workflow. It runs manually, or when pushing a `notary-webhook-v*` tag.

## GitHub App setup

Create a GitHub App owned by the Fluxzero organization:

- Name: `Fluxzero Notary Webhook`
- Homepage URL: `https://fluxzero.io`
- Webhook: disabled
- Repository permissions:
  - Contents: Read and write
  - Metadata: Read-only
- Install it only on `fluxzero-io/fluxzero-cli`

After creating the app:

1. Copy the App ID to `NOTARY_WEBHOOK_GITHUB_APP_ID`.
2. Open the app installation for `fluxzero-io/fluxzero-cli` and copy the installation id from the URL to `NOTARY_WEBHOOK_GITHUB_APP_INSTALLATION_ID`.
3. Generate a private key, download the `.pem`, base64 encode the PEM file, and store it as `NOTARY_WEBHOOK_GITHUB_APP_PRIVATE_KEY_BASE64`.

```bash
base64 -i Fluxzero-Notary-Webhook.private-key.pem | tr -d '\n' | pbcopy
```

The Worker accepts both PKCS#8 (`BEGIN PRIVATE KEY`) and PKCS#1 (`BEGIN RSA PRIVATE KEY`) PEM keys.

## Local checks

```bash
npm ci
npm test
npm run dev
```

## Manual dispatch test

After deployment, send a test webhook for an existing source run:

```bash
curl -i -X POST \
  "https://fluxzero-notary-webhook.<workers-subdomain>.workers.dev/apple-notary/<token>?source_run_id=<run-id>&version=<version>&repository=fluxzero-io/fluxzero-cli" \
  -H "Content-Type: application/json" \
  -d '{"submissionId":"00000000-0000-4000-8000-000000000000"}'
```

Use a real submission id when testing the full staple/publish path.
