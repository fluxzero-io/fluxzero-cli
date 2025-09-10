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
import java.nio.file.Files

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
                    val zipSize = result.size!!

                    try {
                        // Set headers including Content-Length
                        call.response.header(
                            HttpHeaders.ContentDisposition,
                            ContentDisposition.Attachment.withParameter(
                                ContentDisposition.Parameters.FileName,
                                "${request.name}.zip"
                            ).toString()
                        )
                        call.response.header(HttpHeaders.ContentType, ContentType.Application.Zip.toString())
                        call.response.header(HttpHeaders.ContentLength, zipSize.toString())
                        call.response.header(HttpHeaders.ContentEncoding, "identity")
                        call.response.status(HttpStatusCode.OK)
                        
                        // Stream the file content
                        call.respondOutputStream(ContentType.Application.Zip) {
                            Files.newInputStream(zipFile).use { input ->
                                input.copyTo(this)
                            }
                        }
                    } finally {
                        // Clean up the temporary zip file
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