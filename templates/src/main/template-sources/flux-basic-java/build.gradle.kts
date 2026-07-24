import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
    id("org.springframework.boot") version "3.5.16"
    id("io.fluxzero.tools.gradle.plugin") version "@fluxzeroPluginVersion@"
}

group = "com.example"
version = "0.0.1-SNAPSHOT"

fluxzero {
    projectFiles {
        // Agent guidance comes from the installed integration. Keep the sync task available as an explicit opt-in.
        enabled.set(false)
    }
    packagePublishing {
        packageName.set("flux-basic-java")
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
val jettyVersion = "12.1.11"
val lombokVersion = "1.18.46"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
    mavenLocal()
}

dependencies {
    implementation(platform("io.fluxzero:fluxzero-bom:$fluxzeroVersion"))
    implementation(platform("org.eclipse.jetty:jetty-bom:$jettyVersion"))
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    developmentOnly(platform(SpringBootPlugin.BOM_COORDINATES))
    annotationProcessor(platform("io.fluxzero:fluxzero-bom:$fluxzeroVersion"))
    testImplementation(platform("io.fluxzero:fluxzero-bom:$fluxzeroVersion"))
    testImplementation(platform("org.eclipse.jetty:jetty-bom:$jettyVersion"))
    testImplementation(platform(SpringBootPlugin.BOM_COORDINATES))

    implementation("org.springframework.boot:spring-boot-starter")
    developmentOnly("org.springframework.boot:spring-boot-devtools")

    implementation("io.fluxzero:sdk")
    implementation("io.fluxzero.idp:client:$fluxzeroIdpVersion")
    compileOnly("org.projectlombok:lombok:$lombokVersion")
    annotationProcessor("org.projectlombok:lombok:$lombokVersion")
    annotationProcessor("io.fluxzero:sdk")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.fluxzero:sdk") {
        artifact {
            classifier = "tests"
        }
    }
    testImplementation("io.fluxzero.idp:test-support:$fluxzeroIdpVersion")
    testRuntimeOnly("ch.qos.logback:logback-classic:1.5.38")

    testCompileOnly("org.projectlombok:lombok:$lombokVersion")
    testAnnotationProcessor("org.projectlombok:lombok:$lombokVersion")
}

tasks.withType<Test> {
    useJUnitPlatform()
    systemProperty("fluxzero.maven.enabled", "true")
}

tasks.bootJar {
    archiveFileName.set("app.jar")
}
