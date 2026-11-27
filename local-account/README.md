# Local account component

`io.fluxzero.tools:fluxzero-local-account` is the shared account lifecycle used by the CLI and standalone Dev Server.
It requires Java 21 or later. The CLI embeds it in its runnable JAR and native distribution; Dev Server consumes
the separately published artifact. It does not start an account daemon.

## Account operations

`LocalAccountService.status()` reads only a private, non-secret local projection. It does not access Keychain,
open the browser, contact an issuer, or create account files. Remembered status is not proof of current Cloud rights.
Construction and close do not initialize credential storage. Local development must not depend on these operations.

`startLogin()` opens the browser asynchronously after user intent. Poll `status()` or use the bounded `awaitLogin()`.
The callback uses an available IPv4 loopback port and the exact `/fluxzero/account/callback` path. State, nonce,
PKCE S256, signed ID-token verification and an ES256 DPoP key bind the exchange. Cancellation from another local
surface invalidates the operation and releases the callback. No credential is returned through the public API.
`startLogin(true)` requests a fresh sign-in with `prompt=login`; ordinary sign-in may reuse the browser's session.

`refresh()` is an explicit account/Cloud action, never a startup or background-status operation. A process lock
serializes renewal. Keychain atomically updates the existing login item, retaining its OS access approvals. A new
login gets a new item so a late renewal cannot overwrite a different account. If storage commits but its answer
or the metadata update is lost, the next explicit action reads the saved session and repairs the projection.
Transport errors preserve credentials. The IDP's bounded refresh replay window permits retry of a lost response;
outside that window the existing replay/revocation policy applies.

`logout()` clears the local reference and advances its generation before contacting the issuer. In-flight login
or refresh cannot restore it. Online revocation targets that delegated session; failure or offline operation
produces a separate notice that server revocation was not confirmed. Logout does not open credential dialogs.

`startLogoutEverywhere()` opens a focused confirmation at the configured account session manager. Global sign-out
uses the IDP's existing browser session and CSRF-protected logout-all action; native profile tokens gain no new authority.
Its bounded, non-secret pending action is shared between local surfaces. A temporary loopback listener receives only
a correlated completion/cancellation hint at `/fluxzero/account/logout/callback`. This hint carries no credentials
and never proves revocation. The existing account worker verifies the native session after completion and closes
the listener on completion, cancellation, timeout or shutdown. Status readers only observe metadata; they do not
open secure storage or contact the issuer. No manual waiting or checking controls are needed in Devboard.
`checkSession(interactive)` explicitly verifies this native session through DPoP-protected UserInfo and clears
it only after confirmed expiry or revocation. Network and storage errors preserve the session. Background return
checks use `interactive=false`, so they cannot open Keychain dialogs. `cancelBrowserAction()` remains a compatibility action;
it cannot undo a server-side sign-out. Passive `status()` remains local and never performs this check.

CLI equivalents are `fz auth login`, `fz auth login --use-another-account`, `fz auth status --json`,
`fz auth status --check`, `fz auth status --refresh` and `fz auth logout`. Use `--check` to validate a remembered
session explicitly; `--refresh` renews it when needed. No command prints tokens or keys.

## Storage and configuration

The macOS adapter calls Security.framework directly. Tokens and the private DPoP key stay in Keychain, not in
subprocess arguments, project files, browser storage or diagnostics. The first explicit access from a different
executable can require macOS approval. There is no plaintext fallback. Windows uses Credential Manager generic credentials, with an atomic manifest and bounded chunks for sessions larger than one credential blob. Linux uses libsecret and the desktop Secret Service on the user session bus. Missing libsecret, no session bus/keyring, a locked collection or denied access produces a recoverable storage error; there is no file or plaintext credential fallback. Background operations never execute Secret Service prompts. Linux desktop/keyring dependencies must be installed by the user or their system administrator; Fluxzero does not launch an unprotected keyring for ordinary use.
For the macOS login keychain, the adapter also scopes the process interaction policy around each serialized
operation and restores its previous value. This covers application access prompts in addition to authentication
prompts. Background operations fail with a recoverable storage issue; Devboard offers an explicit Unlock secure
storage action rather than opening an unsolicited dialog or keeping a sign-out spinner waiting for OS approval.

All configuration is user/process-level. Never derive an issuer or account resource from an untrusted project.
System properties use `fluxzero.account.<name>`; environment equivalents are:

| Variable | Default / meaning |
| --- | --- |
| `FLUXZERO_ACCOUNT_ENVIRONMENT` | `cloud`; separates local and production credentials |
| `FLUXZERO_ACCOUNT_ISSUER` | `https://login.fluxzero.io` |
| `FLUXZERO_ACCOUNT_CLIENT_ID` | `fluxzero-cli`, provisioned once by the platform |
| `FLUXZERO_ACCOUNT_ACCOUNT_URL` | Issuer plus `/account` |
| `FLUXZERO_ACCOUNT_SESSIONS_URL` | Issuer plus `/account/sessions` |
| `FLUXZERO_ACCOUNT_DASHBOARD_URL` | `https://dashboard.fluxzero.io`; override explicitly for local Dashboard |
| `FLUXZERO_ACCOUNT_RESOURCE` | Optional exact resource URI |
| `FLUXZERO_ACCOUNT_DIRECTORY` | `~/.fluxzero/account`; private metadata and process locks |
| `FLUXZERO_ACCOUNT_OFFLINE_ACCESS` | `false`; request the native client's consented refresh profile explicitly |

Use the same configuration when starting CLI, MCP and Devboards. Credential identity includes environment,
issuer, client and resource. Only HTTPS or explicit loopback HTTP is accepted; discovery endpoints must belong
to the configured issuer origin. Shared subject sectors are platform-provisioned, never selected by the CLI.
System properties take precedence over environment variables, followed by the defaults above. The environment
name separates storage; it does not automatically change the issuer or Dashboard URL.

Metadata is versioned and replaced atomically with private filesystem permissions. Diagnostics contain only
fixed application failure codes and timestamps. They never contain provider bodies or protocol credentials.

## Local development

Build/install the candidate shared module before building a candidate Dev Server:

```sh
./gradlew :local-account:test :local-account:publishToMavenLocal -PappVersion=1-SNAPSHOT
```

For the real local IDP, use its `account-local` profile and prepare with `--with-cli-account`. Set the same local
issuer/environment for each surface; enable offline access only with its explicit native refresh registration.
All local mail must use the IDP's Rebound sink. The public release requires backend provisioning and the published
shared artifact before dependent clients, with macOS Intel/Apple Silicon, Windows x64 and Linux x64 qualification.

`FLUXZERO_TEST_KEYCHAIN=true ./gradlew :local-account:test --tests '*MacKeychainStoreTest'` enables the opt-in
real Keychain test. It creates and removes only its own synthetic item. Regular tests use an isolated in-memory
store and a signed local HTTP fixture; no live issuer or email is used.

## Local browser return pages

`AccountBrowserContext` describes the initiating surface. `startLogin(boolean, context)` and
`startLogoutEverywhere(context)` render local completion pages with embedded Devboard icons and no hosted assets.
A Devboard context may contain only an HTTP loopback URL at the exact `/_fluxzero/dev/` path, without credentials
or query parameters. The Dev Server also binds that destination to its verified request origin. Context remains
in memory and never enters authorization parameters, account metadata or project files. The Open Devboard action
uses the browser displaying this page; it does not claim to focus another browser or agent window.
Terminal and agent contexts offer matching return guidance. Host-specific agent deep links require an explicit,
supported integration and are never inferred from client names or accepted as arbitrary redirect URLs.

## Platform qualification

The PR/release native matrix builds the same CLI on macOS Intel/Apple Silicon, Windows x64 and Linux x64. A separate, unpublished test artifact exercises real OS storage across process exits, large session updates, deletion, private metadata, and a signed loopback issuer through login, instance restart, expiry, DPoP refresh, verification and logout. Both JVM and native-image probes run; no production issuer, credentials or email are used. The synthetic callback driver does not prove real browser SSO or production account continuity.

On Linux the CI job creates its own temporary session bus and synthetic unlocked keyring. This is test setup only, not a production fallback. Local desktop users need libsecret and a running Secret Service implementation (for example GNOME Keyring or a compatible KWallet service). Browser login uses `xdg-open`; Windows uses `ShellExecuteW`, and macOS uses `open`.

References: [Windows credential blobs](https://learn.microsoft.com/en-us/windows/win32/api/wincred/ns-wincred-credentialw), [libsecret encrypted D-Bus secret encoding](https://gnome.pages.gitlab.gnome.org/libsecret/method.Service.encode_dbus_secret.html).
