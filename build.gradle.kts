import org.apache.tools.ant.filters.ReplaceTokens

plugins {
    application
    kotlin("jvm") version "2.1.20"
    id("com.gradleup.shadow") version "8.3.6"
}

group = "host.flux.cli"
version = (project.properties["appVersion"] ?: "1.0.0") as String

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.github.ajalt.clikt:clikt:5.0.3")
    implementation("com.github.ajalt.clikt:clikt-markdown:5.0.3")
    implementation("org.jline:jline:3.30.0")

    testImplementation(kotlin("test"))
    testImplementation("io.mockk:mockk:1.14.2")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}

application {
    mainClass = "host.flux.cli.MainKt"
}

tasks.shadowJar {
    mergeServiceFiles()
}

sourceSets {
    main {
        resources {
            srcDir("build/generated/resources")
        }
    }
}

val zipTemplates by tasks.registering {
    group = "build"
    description = "Archives each subfolder inside templates as a zip and moves them to build/"

    val templatesDir = file("templates")
    val outputDir = file("build/generated/resources/templates")


    inputs.dir(templatesDir)
    outputs.dir(outputDir)

    doLast {
        if (!templatesDir.exists() || !templatesDir.isDirectory) {
            throw GradleException("templates directory does not exist")
        }
        // Get all subdirectories in templates
        val templateFolders = templatesDir.listFiles { file -> file.isDirectory } ?: arrayOf()

        val indexFile = outputDir.resolve("templates.csv")
        val indexEntries = mutableListOf<String>()

        templateFolders.forEach { folder ->
            val folderName = folder.name
            val zipFileName = "$folderName.zip"
            val outputFile = File(outputDir, zipFileName)

            println("Archiving template folder: $folderName")

            // Run git archive command
            val command = listOf("git", "archive", "--format=zip", "HEAD:templates/$folderName")

            val processBuilder = ProcessBuilder(command)
                .redirectOutput(outputFile)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
            val process = processBuilder.start()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw GradleException("git archive failed for folder: $folderName")
            }

            indexEntries.add(folderName)

            println("Created zip: ${outputFile.absolutePath}")
        }
        indexFile.writeText(indexEntries.joinToString("\n"))
    }
}

tasks.named("processResources") {
    dependsOn(zipTemplates)
}

tasks.register<Copy>("generateScripts") {
    val scriptsOutputDir = layout.buildDirectory.dir("release-scripts")
    from("scripts") {
        include("install.sh.template")
        filter<ReplaceTokens>(
            "tokens" to mapOf(
                "VERSION" to version,
            )
        )
    }
    rename("install.sh.template", "install.sh")
    into(scriptsOutputDir)

    doLast {
        file(layout.buildDirectory.file("release-scripts/install.sh")).setExecutable(true)
    }
}
