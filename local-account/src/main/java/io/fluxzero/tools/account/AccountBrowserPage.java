package io.fluxzero.tools.account;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Self-contained local completion page, usable offline without contacting a hosted asset service. */
final class AccountBrowserPage {
    static final String CSP = "default-src 'none'; img-src data:; style-src 'unsafe-inline'; "
            + "script-src 'sha256-UxVXWyAt9U/Kult2HhuIFYy6Qb9b3amlNkkrXDu38Zg='; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

    static String render(String message, AccountBrowserContext context) {
        String light = icon("light"), dark = icon("dark");
        String place = switch (context.source()) {case DEVBOARD -> "Devboard";case AGENT -> "your agent";case TERMINAL -> "your terminal";};
        String brand = context.source() == AccountBrowserContext.Source.DEVBOARD ? "Devboard" : "Developer Tools";
        String link = context.returnTo() == null ? "" : "<a class=primary href=\"" + escape(context.returnTo().toASCIIString())
                + "\">Open Devboard<span aria-hidden=true> &rarr;</span></a>";
        return """
                <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                <meta name="color-scheme" content="light dark"><title>Return to local development · Fluxzero</title>
                <link rel="icon" type="image/svg+xml" href="%s"><style>
                :root{color-scheme:light dark;font-family:Roboto,-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;
                  --bg:#edf2f9;--surface:#fff;--text:#12233d;--muted:#61718a;--border:#d9e2ef;--accent:#0d6efd;--on-accent:#fff;--soft:#e8f1ff;--divider:#b6cbd8}
                *{box-sizing:border-box}body{margin:0;min-height:100svh;display:grid;place-items:center;padding:32px 20px;background:var(--bg);color:var(--text)}
                main{width:100%%;max-width:480px;padding:40px;background:var(--surface);border:1px solid var(--border);border-radius:14px;box-shadow:0 10px 28px #12233d08}
                .brand{display:flex;align-items:center;gap:14px;margin-bottom:32px;font-size:20px;font-weight:600;line-height:1.25}.brand img{width:64px;height:68px;object-fit:contain}
                .brand span{display:block;font-size:16px;font-weight:500;margin-top:3px}.dark{display:none}
                .eyebrow{font-size:12px;color:var(--muted);margin:0 0 10px;letter-spacing:.04em}h1{font-size:28px;line-height:1.25;font-weight:650;letter-spacing:-.4px;margin:0 0 18px}
                p{font-size:15px;line-height:1.65;color:var(--muted);margin:0}.message{margin-bottom:12px}.actions{margin-top:28px}
                .primary{display:inline-flex;align-items:center;gap:12px;padding:11px 16px;min-height:44px;border-radius:6px;background:var(--accent);color:var(--on-accent);text-decoration:none;font-size:14px;font-weight:500}
                .primary:hover{filter:brightness(.94)}a:focus-visible{outline:3px solid var(--accent);outline-offset:4px}
                @media(prefers-color-scheme:dark){:root{--bg:#13243a;--surface:#1b3353;--text:#f7fbff;--muted:#b6c4d9;--border:#3b526d;--accent:#8bc5ff;--on-accent:#12233d}.light{display:none}.dark{display:block}}
                @media(max-width:480px){body{padding:20px 16px}main{padding:28px 24px}.brand{margin-bottom:28px}h1{font-size:25px}}
                </style></head><body><main aria-labelledby="return-title"><div class="brand"><img class="light" src="%s" alt=""><img class="dark" src="%s" alt=""><div>Fluxzero<span>%s</span></div></div>
                <div class="eyebrow">LOCAL DEVELOPMENT</div><h1 id="return-title">Return to local development</h1>
                %s<p>You can close this tab and continue in %s.</p>%s</main>
                <script>history.replaceState(null,'','/fluxzero/account/complete');</script></body></html>
                """.formatted(light, light, dark, brand, message.isBlank() ? "" : "<p class=message>" + escape(message) + "</p>", place, link.isEmpty() ? "" : "<div class=actions>" + link + "</div>");
    }

    private static String icon(String theme) {
        try (var stream = AccountBrowserPage.class.getResourceAsStream("/io/fluxzero/tools/account/devboard-" + theme + ".svg")) {
            if (stream == null) throw new IllegalStateException("Local account icon is unavailable");
            return "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(stream.readAllBytes());
        } catch (java.io.IOException e) {throw new IllegalStateException("Local account icon is unavailable", e);}
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
