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

Fluxzero Cloud and the dev server supply the issuer, application identity and key material.
The BFF library owns login, encrypted shared sessions, refresh, CSRF and logout.
`AppOidcUserMapper` implements `FluxzeroOidcUserMapper` and maps the verified `FluxzeroOidcUser` to the
application's `Sender` using typed name and email accessors. It is discovered through
`META-INF/services/io.fluxzero.idp.bff.OidcUserMapper`. The template ignores the request parameter;
applications may use it for policies such as explicitly authorized impersonation. Current roles come
from the application profile. `SenderProvider` continues to handle internal messages and explicit bearer tokens.
The `io.fluxzero.idp:test-support` dependency supplies the issuer for focused authentication tests.

Set `fluxzero.auth.oidc.disabled=true` to opt out. A custom callback path via
`fluxzero.auth.oidc.redirect-uri` requires an application handler calling `OidcIntegration.callback`.

Coding agents should use the installed Fluxzero plugin's `fluxzero-dev` MCP server. It starts or reuses this
environment and exposes cursored build, reload, problem, log, and test feedback. While it is active, do not run a
second wrapper build, test process, application, watcher, or continuous log command.

## API Endpoints

- **GET** `/api/auth` - Discover the application-specific browser helper
- **GET** `/auth/<applicationId>/login` - Start browser login and receive its callback
- **GET** `/auth/<applicationId>/session` - Current BFF session and CSRF token
- **POST** `/auth/<applicationId>/logout` - End the session, with origin and CSRF validation
- **GET** `/api/me` - Current application profile
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
