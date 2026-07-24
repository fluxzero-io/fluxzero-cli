/**
 * Application-owned authentication glue for the Fluxzero BFF OIDC flow.
 *
 * The Fluxzero IDP client module provides reusable OIDC protocol helpers, but this package owns the
 * browser session and the mapping from IDP subject to application Sender. That mapping is
 * intentionally application-specific.
 *
 * For production, AppAuthProperties points at a configured Fluxzero IDP tenant. For local
 * development tests, the test classpath can contribute the local stub IDP and token validator while
 * this package keeps the same BFF login, callback, session and SenderProvider shape.
 */
package com.example.app.authentication;
