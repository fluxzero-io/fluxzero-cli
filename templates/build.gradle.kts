plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // YAML parsing for refactor.yaml files
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.0")
    
    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testImplementation("io.mockk:mockk:1.14.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val zipTemplates by tasks.registering {
    group = "build"
    description = "Archives each subfolder inside templates as a zip and moves them to build/"

    val templatesDir = file("src/main/templates")
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
            val command = listOf("git", "archive", "--format=zip", "HEAD:templates/src/main/templates/$folderName")

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

sourceSets {
    main {
        resources {
            srcDir("build/generated/resources")
        }
    }
}

tasks.named("processResources") {
    dependsOn(zipTemplates)
}

tasks.test {
    useJUnitPlatform()
}