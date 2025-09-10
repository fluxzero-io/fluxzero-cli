package host.flux.api.routes

import host.flux.api.models.InitFailure
import host.flux.api.models.InitRequest
import host.flux.api.models.TemplateResponse
import host.flux.api.models.VersionResponse
import host.flux.api.services.ApiScaffoldService
import host.flux.templates.services.TemplateService
import host.flux.templates.services.VersionUtils
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.pipeline.*
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path

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
                    
                    // Schedule cleanup after response is completely sent
                    call.response.pipeline.intercept(ApplicationSendPipeline.Engine) {
                        proceed() // Let the normal response processing continue
                        // This runs after the response is fully sent
                        Files.deleteIfExists(zipFile)
                    }
                    
                    // Stream the file directly
                    call.respondFile(zipFile.toFile())


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