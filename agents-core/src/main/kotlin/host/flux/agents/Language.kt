package host.flux.agents

/**
 * Represents the programming language of a Fluxzero project.
 */
enum class Language(val assetSuffix: String) {
    KOTLIN("kotlin"),
    JAVA("java");

    companion object {
        fun fromString(value: String): Language? {
            return entries.find { it.name.equals(value, ignoreCase = true) }
        }
    }
}
