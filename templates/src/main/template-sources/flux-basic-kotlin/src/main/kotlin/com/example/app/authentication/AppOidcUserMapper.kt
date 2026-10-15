package com.example.app.authentication

import io.fluxzero.idp.bff.FluxzeroOidcUser
import io.fluxzero.idp.bff.FluxzeroOidcUserMapper
import io.fluxzero.sdk.web.WebRequest

/** Translate verified identity into application-owned accounts and current roles. */
class AppOidcUserMapper : FluxzeroOidcUserMapper {
    override fun map(user: FluxzeroOidcUser, request: WebRequest): Sender =
        AppUsers.ensureAppUser(user.issuer(), user.subject(), user.name(), user.email())
}
