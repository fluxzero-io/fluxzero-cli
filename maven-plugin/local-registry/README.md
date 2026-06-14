# Local Registry Chain

This directory contains a local Docker Compose harness for `fluxzero:push-image`.

It runs the same frontend shape the Maven plugin uses in production:

- Maven plugin pushes to `/v2/...`.
- Authentication is HTTP Basic with a Fluxzero GitHub-style HMAC token as password.
- The local registry proxy inserts the team prefix.
- Zot is the OCI registry backend.

Start it from the `fluxzero-cli` repository:

```bash
docker compose -f maven-plugin/local-registry/docker-compose.yml up
```

In another terminal, publish the local plugin and push an example:

```bash
./gradlew :maven-plugin:publishToMavenLocal

export MAVEN_OPTS="-Djavax.net.ssl.trustStore=$PWD/.local-registry/certs/truststore-with-defaults.jks -Djavax.net.ssl.trustStorePassword=changeit"
export FLUXZERO_REGISTRY_HOST="https://127.0.0.1:8443"
export FLUXZERO_REGISTRY_TOKEN="$(node maven-plugin/local-registry/generate-token.js team-a plain-java)"
export FLUXZERO_IMAGE_VERSION="local-dev"

mvn -B -f maven-plugin/examples/plain-java/pom.xml package fluxzero:push-image
```

If you are testing from an uncommitted checkout, add `-Dfluxzero.image.allowDirty=true`; the pushed tag will be
`local-dev-dirty`.

Pull from Zot directly to inspect or run the backend image:

```bash
docker pull 127.0.0.1:5100/team-a/plain-java:local-dev
docker run --rm 127.0.0.1:5100/team-a/plain-java:local-dev codex
```

The local registry proxy records request metrics in `.local-registry/proxy-metrics.ndjson`. Use it to verify repeated pushes only send manifests or changed application layers.
