plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.1.20"
    application
    id("com.gradleup.shadow") version "8.3.6"
    id("org.graalvm.buildtools.native") version "0.10.6"
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":templates"))
    
    // Ktor server dependencies
    implementation("io.ktor:ktor-server-core:2.3.12")
    implementation("io.ktor:ktor-server-cio:2.3.12")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")
    implementation("io.ktor:ktor-server-cors:2.3.12")
    implementation("io.ktor:ktor-server-status-pages:2.3.12")
    
    // Logging implementation
    implementation("ch.qos.logback:logback-classic:1.4.14")
    
    // Testing dependencies
    testImplementation("io.ktor:ktor-server-test-host:2.3.12")
    testImplementation("io.ktor:ktor-client-content-negotiation:2.3.12")
}

application {
    mainClass = "host.flux.api.ApplicationKt"
}

tasks.shadowJar {
    mergeServiceFiles()
    archiveClassifier.set("")
    archiveBaseName.set("flux-api")
}

// Fix dependency ordering issues
tasks.named("distZip") {
    dependsOn("shadowJar")
}

tasks.named("distTar") {
    dependsOn("shadowJar")
}

tasks.named("startScripts") {
    dependsOn("shadowJar")
}

tasks.named("startShadowScripts") {
    dependsOn("jar")
}

graalvmNative {
    toolchainDetection.set(true)
    
    // Enable the tracing agent for reflection discovery
    agent {
        defaultMode.set("standard")
        builtinCallerFilter.set(true)
        builtinHeuristicFilter.set(true)
        enableExperimentalPredefinedClasses.set(false)
        enableExperimentalUnsafeAllocationTracing.set(false)
        trackReflectionMetadata.set(true)
        modes {
            standard {}
        }
    }
    
    binaries {
        named("main") {
            imageName.set("flux-api")
            mainClass.set("host.flux.api.ApplicationKt")
            buildArgs.addAll(
                "--no-fallback",
                "--install-exit-handlers",
                "--enable-url-protocols=https",
                "--report-unsupported-elements-at-runtime",
                "--initialize-at-build-time=kotlin",
                "-H:+AddAllCharsets",
                "-H:+ReportExceptionStackTraces"
            )
            // Use metadata repository for better Ktor support
            metadataRepository {
                enabled.set(true)
            }
        }
    }
    
    binaries.all {
        resources.autodetect()
    }
}

// Task to generate reflection config using the tracing agent
tasks.register("generateReflectionConfig") {
    group = "native"
    description = "Generate reflection configuration using GraalVM tracing agent"
    
    doLast {
        val configDir = file("src/main/resources/META-INF/native-image")
        configDir.mkdirs()
        
        exec {
            workingDir = project.projectDir
            commandLine = listOf(
                "java",
                "-agentlib:native-image-agent=config-output-dir=${configDir.absolutePath}",
                "-jar", "build/libs/flux-api.jar"
            )
        }
    }
    
    dependsOn("shadowJar")
}