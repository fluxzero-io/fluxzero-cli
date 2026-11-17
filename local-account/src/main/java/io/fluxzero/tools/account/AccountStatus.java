package io.fluxzero.tools.account;

/** Non-secret cached status. This is not proof of current cloud authorization or reachability. */
public record AccountStatus(String state, String displayName, String email, String accountUrl,
                            String environment, long expiresAt, boolean renewable, String operationId,
                            String message, String dashboardUrl, String sessionsUrl, String pendingAction) {
    public AccountStatus(String state, String displayName, String email, String accountUrl, String environment,
                         long expiresAt, boolean renewable, String operationId, String message) {
        this(state, displayName, email, accountUrl, environment, expiresAt, renewable, operationId, message, null, null, null);
    }
}
