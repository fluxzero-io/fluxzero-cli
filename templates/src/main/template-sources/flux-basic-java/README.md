# Fluxzero Base Java Project

This is a foundation project for Fluxzero applications. It serves as a base for developers and agents to build new applications.

This generated starter is intentionally generic. Replace or remove the example user mapping, authentication, endpoints,
tests, and frontend according to the requirements of the application you are building.

## What's Included

A foundational application with the following features already configured:
- **User Mapping**: Minimal example users for authenticated sessions and RBAC
- **Authentication**: Fluxzero IDP BFF login flow, session cookie, and role-based access control (RBAC)
- **Fluxzero Integration**: Pre-configured automated development environment

## Quick Start

Install the Fluxzero CLI and start the complete development environment:

```bash
fz dev
```

This starts:
- the embedded Fluxzero runtime and proxy
- the managed local identity provider
- the Spring Boot application, with rolling replacement after source changes
- affected tests in the background

The main application code reads a normal Fluxzero IDP tenant from the `fluxzero.auth.*` properties.
For deployed environments, provide those values through environment variables, system properties or
`src/main/resources/application.properties`. The dev server supplies the local runtime, proxy, and identity provider;
the application does not bootstrap those services from its test classpath. The `io.fluxzero.idp:test-support`
dependency remains available for focused authentication tests.

Coding agents should use the installed Fluxzero plugin's `fluxzero-dev` MCP server. It starts or reuses this
environment and exposes cursored build, reload, problem, log, and test feedback. While it is active, do not run a
second wrapper build, test process, application, watcher, or continuous log command.

## API Endpoints

- **GET** `/app/login` - Start browser login
- **GET** `/app/callback` - OIDC callback
- **GET** `/app/auth/session` - Current BFF session
- **GET** `/api/users` - List all users
- **POST** `/api/users` - Create new user

## Testing

The development environment automatically selects affected tests after changes. Run the `All tests` IDE
configuration or the project wrapper only for explicit full-suite, CI, or fallback verification.

## Deployment

The generated `.github/workflows/deploy-to-fluxzero-cloud.yml` matches the selected Maven or Gradle build. After this
repository is connected to a Fluxzero organisation, run the workflow with a cluster and application name. GitHub OIDC
provides short-lived credentials, so no API-key secret or organisation id is required.

The workflow tests the project first, publishes a layered image with the Fluxzero build plugin, and deploys the same
immutable `sha-<commit>` tag. The plugin resolves the organisation namespace and pushes
`registry.fluxzero.io/<organisationId>/<artifactId>:<tag>`; the deploy action needs only the artifact id and tag.
