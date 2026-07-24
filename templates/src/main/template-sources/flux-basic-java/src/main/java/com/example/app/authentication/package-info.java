/**
 * Application-owned authentication glue for the Fluxzero BFF OIDC flow.
 * <p>
 * The Fluxzero IDP client module provides reusable OIDC protocol helpers, but this package owns the
 * domain mapping from validated OIDC claims to application users, roles and sessions. That separation
 * is intentional: generated applications can use Fluxzero IDP as the default authentication provider
 * while keeping customer-specific identity, provisioning and permission decisions in their own
 * backend code.
 * <p>
 * For production, {@code AppAuthProperties} points at a configured Fluxzero IDP tenant. For local
 * development tests, the test classpath can contribute the local stub IDP and token validator while
 * this package keeps the same BFF login, callback, session and {@code SenderProvider} shape.
 */
package com.example.app.authentication;
