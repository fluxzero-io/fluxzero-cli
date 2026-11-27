package io.fluxzero.tools.account;

import org.junit.jupiter.api.Test;
import java.net.URI;
import static org.junit.jupiter.api.Assertions.*;

class AccountBrowserPageTest {
    @Test void stylesTheLocalPageWithEmbeddedDevboardIconsAndAnEscapedReturnLink() {
        var context=AccountBrowserContext.devboard(URI.create("http://localhost:4741/_fluxzero/dev/#cloud/overview"));
        String page=AccountBrowserPage.render("<untrusted>",context);
        assertTrue(page.contains("data:image/svg+xml;base64,"));
        assertTrue(page.contains("rel=\"icon\""));
        assertTrue(page.contains("Open Devboard"));
        assertTrue(page.contains(context.returnTo().toString()));
        assertTrue(page.contains("&lt;untrusted&gt;"));
        assertFalse(page.contains("<untrusted>"));
        assertTrue(page.contains("prefers-color-scheme:dark"));
        assertTrue(AccountBrowserPage.CSP.contains("img-src data:"));
    }
    @Test void recognisesTheAgentWithoutInventingAHostDeepLink() {
        String page=AccountBrowserPage.render("",AccountBrowserContext.agent());
        assertTrue(page.contains("continue in your agent"));
        assertFalse(page.contains("class=primary href="));
    }
    @Test void rejectsRemoteAndUnrelatedReturnDestinations() {
        for(String value:new String[]{"https://example.com/_fluxzero/dev/", "http://localhost:4741/other", "http://user@localhost:4741/_fluxzero/dev/", "http://localhost:4741/_fluxzero/dev/?token=private", "javascript:alert(1)"})
            assertThrows(IllegalArgumentException.class,()->AccountBrowserContext.devboard(URI.create(value)));
    }
}
