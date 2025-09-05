package host.flux.api.routes

import host.flux.api.models.*
import host.flux.templates.services.*
import host.flux.templates.models.InstallResult
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
                val versionInfo = VersionService.getVersionInfo()
                call.respond(VersionResponse(
                    currentVersion = versionInfo.currentVersion,
                    latestVersion = versionInfo.latestVersion,
                    hasUpdate = versionInfo.hasUpdate
                ))
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
                    
                    call.respond(InitResponse(
                        success = result.success,
                        message = result.message,
                        outputPath = result.outputPath,
                        error = result.error
                    ))
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
            
            // Upgrade CLI (note: this won't work in API context but included for completeness)
            post("/upgrade") {
                try {
                    val installService = DefaultInstallationService()
                    val result = installService.install()
                    
                    when (result) {
                        is InstallResult.Upgraded -> call.respond(InstallResponse(
                            success = true,
                            message = "Upgraded successfully",
                            fromVersion = result.fromVersion,
                            toVersion = result.toVersion
                        ))
                        is InstallResult.FreshInstall -> call.respond(InstallResponse(
                            success = true,
                            message = "Installed successfully",
                            toVersion = result.version
                        ))
                        is InstallResult.AlreadyLatest -> call.respond(InstallResponse(
                            success = true,
                            message = "Already up to date",
                            toVersion = result.currentVersion
                        ))
                    }
                } catch (e: Exception) {
                    call.respond(
                        status = HttpStatusCode.InternalServerError,
                        message = InstallResponse(
                            success = false,
                            message = "Upgrade failed",
                            error = e.message
                        )
                    )
                }
            }
        }
    }
}