package host.flux.api.routes

import host.flux.api.models.InitRequest
import host.flux.api.models.InitResponse
import host.flux.api.models.TemplateResponse
import host.flux.api.models.VersionResponse
import host.flux.templates.services.InitializationService
import host.flux.templates.services.TemplateService
import host.flux.templates.services.VersionUtils
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureRoutes() {
    routing {
        route("/api") {
            // Health check
            get("/health") {
                call.respond(mapOf("status" to "healthy"))
            }

            // Version info
            get("/version") {
                val currentVersion = VersionUtils.getCurrentVersion()
                call.respond(
                    VersionResponse(
                        currentVersion = currentVersion,
                        latestVersion = null, // API doesn't check for updates
                        hasUpdate = false
                    )
                )
            }

            // List templates
            get("/templates") {
                val templateService = TemplateService()
                val templates = templateService.listTemplates()
                call.respond(templates.map {
                    TemplateResponse(name = it.name, description = it.description)
                })
            }

            // Initialize project
            post("/init") {
                try {
                    val request = call.receive<InitRequest>()
                    val initService = InitializationService()

                    val templatesRequest = host.flux.templates.models.InitRequest(
                        template = request.template,
                        name = request.name,
                        outputDir = request.outputDir,
                        initGit = request.initGit
                    )

                    val result = initService.initializeProject(templatesRequest)

                    call.respond(
                        InitResponse(
                            success = result.success,
                            message = result.message,
                            outputPath = result.outputPath,
                            error = result.error
                        )
                    )
                } catch (e: Exception) {
                    call.respond(
                        status = HttpStatusCode.BadRequest,
                        message = InitResponse(
                            success = false,
                            message = "Failed to process request",
                            error = e.message
                        )
                    )
                }
            }
        }
    }
}