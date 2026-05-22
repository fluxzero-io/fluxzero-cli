import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

dependencies {
    implementation(project(":project-files"))

    implementation(compose.desktop.currentOs)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

fun desktopPackageVersion(): String {
    val rawVersion = (project.properties["appVersion"] ?: "1.0.0").toString()
    val match = Regex("""^v?(\d+)(?:\.(\d+))?(?:\.(\d+))?""").find(rawVersion)
    val major = match?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it > 0 } ?: 1
    val minor = match?.groupValues?.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "0"
    val patch = match?.groupValues?.getOrNull(3)?.takeIf { it.isNotBlank() } ?: "0"
    return "$major.$minor.$patch"
}

compose.desktop {
    application {
        mainClass = "host.flux.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Exe)
            modules("java.net.http", "java.desktop")
            packageName = "Fluxzero Launchpad"
            packageVersion = desktopPackageVersion()
            description = "Generate Fluxzero projects and open them in local coding agents."
            vendor = "Fluxzero"

            macOS {
                bundleID = "io.fluxzero.desktop"
                dockName = "Fluxzero Launchpad"
                packageName = "Fluxzero Launchpad"
                iconFile.set(project.file("src/main/resources/icons/fluxzero.icns"))
                infoPlist {
                    extraKeysRawXml = """
                        <key>CFBundleURLTypes</key>
                        <array>
                            <dict>
                                <key>CFBundleURLName</key>
                                <string>Fluxzero Launchpad</string>
                                <key>CFBundleURLSchemes</key>
                                <array>
                                    <string>fluxzero</string>
                                </array>
                            </dict>
                        </array>
                    """.trimIndent()
                }
            }

            windows {
                packageName = "Fluxzero Launchpad"
                dirChooser = true
                perUserInstall = true
                iconFile.set(project.file("src/main/resources/icons/fluxzero.ico"))
            }
        }
    }
}

tasks.withType<AbstractJPackageTask>().matching { it.name == "packageDmg" }.configureEach {
    freeArgs.addAll("--icon", project.file("src/main/resources/icons/fluxzero.icns").absolutePath)
}
