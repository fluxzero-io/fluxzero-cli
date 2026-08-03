package host.flux.gradle

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

abstract class DevExtension @Inject constructor(objects: ObjectFactory) {
    val serverVersion: Property<String> = objects.property(String::class.java)
    val mainClass: Property<String> = objects.property(String::class.java)
    val applicationName: Property<String> = objects.property(String::class.java)
    val applications: ListProperty<String> = objects.listProperty(String::class.java).convention(emptyList())
    val profile: Property<String> = objects.property(String::class.java)
    val environment: Property<String> = objects.property(String::class.java).convention("local")
    val port: Property<Int> = objects.property(Int::class.java)
    val idp: Property<String> = objects.property(String::class.java)
    val namespace: Property<String> = objects.property(String::class.java)
    val watch: Property<Boolean> = objects.property(Boolean::class.java).convention(true)
    val compileOnStart: Property<Boolean> = objects.property(Boolean::class.java).convention(true)
    val testsEnabled: Property<Boolean> = objects.property(Boolean::class.java).convention(true)
    val fastCompiler: Property<Boolean> = objects.property(Boolean::class.java).convention(false)
    val frontendCommand: Property<String> = objects.property(String::class.java)
    val frontendDirectory: Property<String> = objects.property(String::class.java)
    val frontendSetupCommand: Property<String> = objects.property(String::class.java)
    val frontendUrl: Property<String> = objects.property(String::class.java)
    val frontendEnabled: Property<Boolean> = objects.property(Boolean::class.java).convention(true)
    val backendPaths: ListProperty<String> = objects.listProperty(String::class.java).convention(emptyList())
    val appArgs: ListProperty<String> = objects.listProperty(String::class.java).convention(emptyList())
    val startupTimeoutMillis: Property<Long> = objects.property(Long::class.java)
    val gracefulShutdownTimeoutMillis: Property<Long> = objects.property(Long::class.java)
    val debounceMillis: Property<Long> = objects.property(Long::class.java)
    val background: Property<Boolean> = objects.property(Boolean::class.java).convention(false)
}
