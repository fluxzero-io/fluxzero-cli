package host.flux.desktop.services

object ProjectNameNormalizer {
    fun normalize(name: String): String {
        return name
            .lowercase()
            .replace(' ', '-')
            .replace(Regex("[^a-z0-9_-]"), "")
            .replace(Regex("[-_]{2,}"), "-")
            .trim('-', '_')
            .take(50)
            .trimEnd('-', '_')
    }
}
