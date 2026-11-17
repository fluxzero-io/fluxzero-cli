package io.fluxzero.tools.account;

/** Non-secret cached status. This is not proof of current cloud authorization or reachability. */
public record AccountStatus(String state, String displayName, String email, String accountUrl,
                            String environment, long expiresAt, boolean renewable, String operationId,
                            String message) {
}
