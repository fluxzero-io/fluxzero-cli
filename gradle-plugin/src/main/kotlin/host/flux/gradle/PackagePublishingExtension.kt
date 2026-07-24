package host.flux.gradle

import host.flux.publishing.JavaPackagePublishSpec
import org.gradle.api.Action
import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

/** Configuration for building and publishing a layered Java OCI package. */
abstract class PackagePublishingExtension @Inject constructor(objects: ObjectFactory) {
    val packageName: Property<String> = objects.property(String::class.java)
    val packageVersion: Property<String> = objects.property(String::class.java)
    val applicationId: Property<String> = objects.property(String::class.java)
    val mainClass: Property<String> = objects.property(String::class.java)
    val images: ListProperty<String> = objects.listProperty(String::class.java)
    val tags: ListProperty<String> = objects.listProperty(String::class.java)
    val baseImage: Property<String> = objects.property(String::class.java)
        .convention(JavaPackagePublishSpec.DEFAULT_BASE_IMAGE)
    val baseImageSource: Property<String> = objects.property(String::class.java).convention("registry")
    val javaToolOptions: Property<String> = objects.property(String::class.java)
        .convention(JavaPackagePublishSpec.DEFAULT_JAVA_TOOL_OPTIONS)
    val labels: MapProperty<String, String> = objects.mapProperty(String::class.java, String::class.java)
    val publishAttempts: Property<Int> = objects.property(Int::class.java)
        .convention(JavaPackagePublishSpec.DEFAULT_PUBLISH_ATTEMPTS)
    val publishRetryDelayMillis: Property<Long> = objects.property(Long::class.java)
        .convention(JavaPackagePublishSpec.DEFAULT_PUBLISH_RETRY_DELAY_MILLIS)

    val authentications: NamedDomainObjectContainer<RegistryAuthentication> =
        objects.domainObjectContainer(RegistryAuthentication::class.java)

    fun authentications(action: Action<NamedDomainObjectContainer<RegistryAuthentication>>) {
        action.execute(authentications)
    }
}

/** One host-bound registry authentication. */
abstract class RegistryAuthentication @Inject constructor(
    private val authenticationName: String,
    objects: ObjectFactory
) : Named {
    val host: Property<String> = objects.property(String::class.java)

    internal val basicConfiguration: BasicAuthentication = objects.newInstance(BasicAuthentication::class.java)
    internal val githubOidcConfiguration: GitHubOidcAuthentication =
        objects.newInstance(GitHubOidcAuthentication::class.java)
    internal var configuredMechanism: AuthenticationMechanism? = null
        private set

    override fun getName(): String = authenticationName

    fun basic(action: Action<BasicAuthentication>) {
        select(AuthenticationMechanism.BASIC)
        action.execute(basicConfiguration)
    }

    fun githubOidc(action: Action<GitHubOidcAuthentication>) {
        select(AuthenticationMechanism.GITHUB_OIDC)
        action.execute(githubOidcConfiguration)
    }

    private fun select(mechanism: AuthenticationMechanism) {
        check(configuredMechanism == null || configuredMechanism == mechanism) {
            "Configure exactly one of basic or githubOidc for registry authentication '$name'."
        }
        configuredMechanism = mechanism
    }
}

abstract class BasicAuthentication @Inject constructor(objects: ObjectFactory) {
    val username: Property<String> = objects.property(String::class.java).convention("")
    val token: Property<String> = objects.property(String::class.java)
}

abstract class GitHubOidcAuthentication @Inject constructor(objects: ObjectFactory) {
    val username: Property<String> = objects.property(String::class.java).convention("")
    val audience: Property<String> = objects.property(String::class.java)
}

internal enum class AuthenticationMechanism {
    BASIC,
    GITHUB_OIDC
}
