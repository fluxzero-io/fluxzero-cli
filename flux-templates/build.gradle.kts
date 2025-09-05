plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Template module has minimal dependencies - only template processing logic
    // Uses built-in Java HTTP client, no external web dependencies
}

val zipTemplates by tasks.registering {
    group = "build"
    description = "Archives each subfolder inside templates as a zip and moves them to build/"

    val templatesDir = file("src/main/resources/templates")
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
            val command = listOf("git", "archive", "--format=zip", "HEAD:flux-templates/src/main/resources/templates/$folderName")

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