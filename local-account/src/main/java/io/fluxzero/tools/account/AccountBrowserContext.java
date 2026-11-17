package io.fluxzero.tools.account;

import java.net.URI;
import java.util.Set;

/** Local presentation context, kept in memory and never included in an identity-provider request. */
public record AccountBrowserContext(Source source, URI returnTo) {
    public enum Source { TERMINAL, DEVBOARD, AGENT }

    public AccountBrowserContext {
        if (source == null) throw new IllegalArgumentException("Account source is required");
        if (returnTo != null && (source != Source.DEVBOARD || !"http".equals(returnTo.getScheme())
                || returnTo.getHost() == null || !Set.of("localhost", "127.0.0.1", "[::1]").contains(returnTo.getHost())
                || returnTo.getRawUserInfo() != null || !"/_fluxzero/dev/".equals(returnTo.getRawPath())
                || returnTo.getRawQuery() != null || returnTo.toString().length() > 4096))
            throw new IllegalArgumentException("Use the local Devboard address");
    }

    public static AccountBrowserContext terminal() {return new AccountBrowserContext(Source.TERMINAL, null);}
    public static AccountBrowserContext agent() {return new AccountBrowserContext(Source.AGENT, null);}
    public static AccountBrowserContext devboard(URI returnTo) {return new AccountBrowserContext(Source.DEVBOARD, returnTo);}
}
