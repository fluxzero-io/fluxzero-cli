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

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }
}
