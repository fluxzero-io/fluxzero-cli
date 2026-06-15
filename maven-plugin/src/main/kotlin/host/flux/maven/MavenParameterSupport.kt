package host.flux.maven

import java.util.Properties

internal object MavenParameterSupport {
    fun firstConfigured(
        userProperties: Properties?,
        propertyName: String,
        environmentVariable: String,
        configuredValue: String?
    ): String? =
        firstNonBlank(
            userProperties?.getProperty(propertyName),
            System.getProperty(propertyName),
            System.getenv(environmentVariable),
            configuredValue
        )

    fun firstConfiguredValue(
        userProperties: Properties?,
        propertyName: String,
        environmentVariable: String,
        configuredValue: String?
    ): String? =
        when {
            userProperties?.containsKey(propertyName) == true -> userProperties.getProperty(propertyName) ?: ""
            System.getProperties().containsKey(propertyName) -> System.getProperty(propertyName) ?: ""
            configuredValue != null -> configuredValue
            System.getenv().containsKey(environmentVariable) -> System.getenv(environmentVariable) ?: ""
            else -> null
        }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }
}
