package com.example.app.authentication;

import io.fluxzero.idp.bff.FluxzeroOidcUser;
import io.fluxzero.idp.bff.FluxzeroOidcUserMapper;
import io.fluxzero.sdk.web.WebRequest;

/** Translate verified identity into application-owned accounts and current roles. */
public class AppOidcUserMapper implements FluxzeroOidcUserMapper {
    @Override
    public Sender map(FluxzeroOidcUser user, WebRequest request) {
        return AppUsers.ensureAppUser(user.issuer(), user.subject(), user.name(), user.email());
    }
}
