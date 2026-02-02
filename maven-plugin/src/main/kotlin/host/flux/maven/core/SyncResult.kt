package host.flux.maven.core

/**
 * Result of a sync operation.
 */
sealed class SyncResult {
    /**
     * Sync was successful - files were updated.
     */
    data class Updated(
        val version: String,
        val filesWritten: List<String>,
        val language: Language
    ) : SyncResult()

    /**
     * No update needed - files are already up to date.
     */
    data class UpToDate(
        val version: String
    ) : SyncResult()

    /**
     * Sync was skipped (e.g., no SDK dependency found).
     */
    data class Skipped(
        val reason: String
    ) : SyncResult()

    /**
     * Sync failed with an error.
     */
    data class Failed(
        val error: String,
        val cause: Throwable? = null
    ) : SyncResult()
}
