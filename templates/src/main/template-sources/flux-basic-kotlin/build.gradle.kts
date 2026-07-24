import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.10"
    id("org.jetbrains.kotlin.plugin.spring") version "2.4.10"
    id("org.jetbrains.kotlin.kapt") version "2.4.10"
    id("org.springframework.boot") version "3.5.16"
    id("io.fluxzero.tools.gradle.plugin") version "@fluxzeroPluginVersion@"
}

group = "com.example.flux"
version = "1.0-SNAPSHOT"

fluxzero {
    projectFiles {
        // Agent guidance comes from the installed integration. Keep the sync task available as an explicit opt-in.
        enabled.set(false)
    }
    packagePublishing {
        packageName.set("flux-basic-kotlin")
        images.add("registry.fluxzero.io/\${organisationId}/\${packageName}")
        tags.add(providers.environmentVariable("FLUXZERO_PACKAGE_VERSION").orElse(project.version.toString()))
        authentications {
            create("fluxzero") {
                host.set("registry.fluxzero.io")
                githubOidc {
                    audience.set("https://cloud.fluxzero.io")
                }
            }
        }
    }
}

val fluxzeroVersion = "1.230.0"
val fluxzeroIdpVersion = "0.15.0"

repositories {
    mavenCentral()
    mavenLocal()
}

dependencies {
    implementation(platform("io.fluxzero:fluxzero-bom:$fluxzeroVersion"))
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:2.4.10"))
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    developmentOnly(platform(SpringBootPlugin.BOM_COORDINATES))
    kapt(platform("io.fluxzero:fluxzero-bom:$fluxzeroVersion"))
    testImplementation(platform("io.fluxzero:fluxzero-bom:$fluxzeroVersion"))
    testImplementation(platform("org.jetbrains.kotlin:kotlin-bom:2.4.10"))
    testImplementation(platform(SpringBootPlugin.BOM_COORDINATES))

    implementation("org.springframework.boot:spring-boot-starter")
    developmentOnly("org.springframework.boot:spring-boot-devtools")

    implementation("io.fluxzero:sdk")
    implementation("io.fluxzero.idp:client:$fluxzeroIdpVersion")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    kapt("io.fluxzero:common")

    implementation("io.github.oshai:kotlin-logging-jvm:7.0.14")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.fluxzero:sdk") {
        artifact {
            classifier = "tests"
        }
    }
    testImplementation("io.fluxzero.idp:test-support:$fluxzeroIdpVersion")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("fluxzero.maven.enabled", "true")
}

tasks.bootJar {
    archiveFileName.set("app.jar")
}

kotlin {
    jvmToolchain(25)
}
