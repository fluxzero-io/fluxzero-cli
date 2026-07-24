package com.example.app;

import io.fluxzero.sdk.web.ServeStatic;
import org.springframework.stereotype.Component;

@Component
@ServeStatic(value = "/", ignorePaths = {"/api/*", "/app/*", "/.well-known/*", "/oauth2/*", "/login", "/userinfo"})
public class UiEndpoint {
}
