package host.flux.api.routes

import host.flux.api.models.HealthResponse
import host.flux.api.models.InitFailure
import host.flux.api.models.InitRequest
import host.flux.api.models.TemplateResponse
import host.flux.api.models.VersionResponse
import host.flux.api.services.ApiScaffoldService
import host.flux.templates.services.ClasspathTemplateService
import host.flux.templates.services.VersionUtils
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path

fun Application.configureRoutes() {
    routing {
        route("/api") {
            // Health check
            get("/health") {
                try {
                    val templateService = ClasspathTemplateService()
                    val templateCount = templateService.listTemplates().size

                    if (templateCount > 0) {
                        call.respond(HealthResponse(
                            status = "healthy",
                            availableTemplates = templateCount
                        ))
                    } else {
                        call.respond(
                            status = HttpStatusCode.ServiceUnavailable,
                            message = HealthResponse(
                                status = "unhealthy",
                                availableTemplates = templateCount
                            )
                        )
                    }
                } catch (e: Exception) {
                    // Log the error for debugging but don't fail completely
                    call.application.log.error("Failed to load templates in health check", e)
                    call.respond(
                        status = HttpStatusCode.ServiceUnavailable,
                        message = HealthResponse(
                            status = "unhealthy",
                            availableTemplates = 0,
                            error = e.message ?: "Unknown error occurred while loading templates"
                        )
                    )
                }
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
                val templateService = ClasspathTemplateService()
                val templates = templateService.listTemplates()
                call.respond(templates.map { template ->
                    TemplateResponse(name = template.name, description = template.description)
                })
            }

            // Initialize project - returns a zip file
            post("/init") {
                try {
                    val request = call.receive<InitRequest>()
                    val apiScaffoldService = ApiScaffoldService()

                    val result = apiScaffoldService.scaffoldProjectAsZip(request)

                    if (!result.success) {
                        call.respond(
                            status = HttpStatusCode.BadRequest,
                            message = InitFailure(
                                error = result.error ?: "Unknown error occurred"
                            )
                        )
                        return@post
                    }

                    val zipFile = result.zipFile!!

                    // Set headers
                    call.response.header(
                        HttpHeaders.ContentDisposition,
                        ContentDisposition.Attachment.withParameter(
                            ContentDisposition.Parameters.FileName,
                            "${request.name}.zip"
                        ).toString()
                    )
                    call.response.header(HttpHeaders.CacheControl, "no-cache, no-store, must-revalidate, no-transform")
                    
                    // Stream the file directly
                    try {
                        call.respondFile(zipFile.toFile())
                    } finally {
                        Files.deleteIfExists(zipFile)
                    }


                } catch (e: Exception) {
                    call.respond(
                        status = HttpStatusCode.InternalServerError,
                        message = InitFailure(
                            error = e.message ?: "Unknown error"
                        )
                    )
                }
            }
        }
    }
}
