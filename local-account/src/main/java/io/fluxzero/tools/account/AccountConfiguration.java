package io.fluxzero.tools.account;

import java.net.URI;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Set;

/** Non-secret, user-level configuration. Never load account authorities from project files. */
public record AccountConfiguration(String environment, URI issuer, String clientId, String resource,
                                   URI accountUrl, Path directory, boolean requestOfflineAccess,
                                   Duration loginTimeout, URI dashboardUrl, URI sessionsUrl) {
    public AccountConfiguration {
        if (environment == null || !environment.matches("[a-zA-Z0-9_-]{1,64}"))
            throw new IllegalArgumentException("Select a valid account environment.");
        requireTrustedTransport(issuer);
        if (issuer.getRawQuery() != null || issuer.getRawFragment() != null || issuer.getRawUserInfo() != null)
            throw new IllegalArgumentException("The account issuer must not contain credentials, a query or a fragment.");
        if (clientId == null || clientId.isBlank() || clientId.length() > 256)
            throw new IllegalArgumentException("An account client is required.");
        if (resource == null) resource = "";
        if (!resource.isEmpty()) requireTrustedTransport(URI.create(resource));
        requireTrustedTransport(accountUrl);
        requireTrustedTransport(dashboardUrl);
        requireTrustedTransport(sessionsUrl);
        directory = directory.toAbsolutePath().normalize();
        if (loginTimeout.isNegative() || loginTimeout.isZero() || loginTimeout.compareTo(Duration.ofMinutes(10)) > 0)
            throw new IllegalArgumentException("The sign-in timeout must be between zero and ten minutes.");
    }

    /** Retains the initial component constructor for embedders. */
    public AccountConfiguration(String environment, URI issuer, String clientId, String resource, URI accountUrl,
                                Path directory, boolean requestOfflineAccess, Duration loginTimeout) {
        this(environment, issuer, clientId, resource, accountUrl, directory, requestOfflineAccess, loginTimeout,
                URI.create("https://dashboard.fluxzero.io"), URI.create(issuer.toString().replaceAll("/$", "") + "/account/sessions"));
    }

    /** Reading defaults does not access credentials, start processes or contact a server. */
    public static AccountConfiguration defaults() {
        String environment = setting("environment", "cloud");
        String issuer = setting("issuer", "https://login.fluxzero.io");
        return new AccountConfiguration(environment, URI.create(issuer), setting("clientId", "fluxzero-cli"),
                setting("resource", ""), URI.create(setting("accountUrl", issuer + "/account")),
                Path.of(setting("directory", Path.of(System.getProperty("user.home"), ".fluxzero", "account").toString())),
                Boolean.parseBoolean(setting("offlineAccess", "false")), Duration.ofMinutes(3),
                URI.create(setting("dashboardUrl", "https://dashboard.fluxzero.io")),
                URI.create(setting("sessionsUrl", issuer.replaceAll("/$", "") + "/account/sessions")));
    }

    private static String setting(String name, String fallback) {
        String value = System.getProperty("fluxzero.account." + name);
        if (value == null) value = System.getenv("FLUXZERO_ACCOUNT_" + name.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(java.util.Locale.ROOT));
        return value == null || value.isBlank() ? fallback : value;
    }

    public String storageId() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((environment + "\n" + issuer + "\n" + clientId + "\n" + resource).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    static void requireTrustedTransport(URI uri) {
        boolean loopback = uri.getHost() != null && (Set.of("127.0.0.1", "[::1]", "::1", "localhost").contains(uri.getHost())
                || uri.getHost().endsWith(".localhost"));
        if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                || !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()) && loopback))
            throw new IllegalArgumentException("Account connections require HTTPS or an explicit local loopback address.");
    }
}
