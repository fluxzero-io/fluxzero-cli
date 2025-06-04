package com.example.flux.tasks

import io.fluxcapacitor.javaclient.FluxCapacitor
import io.fluxcapacitor.javaclient.web.HandleGet
import io.fluxcapacitor.javaclient.web.WebRequest
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture

@Component
class TaskApi {
    @HandleGet("/task/*")
    fun getTask(request: WebRequest): CompletableFuture<Task> {
        // Retrieve the task ID the user has requested
        val taskId = TaskId(request.path.substringAfterLast("/"))
        return FluxCapacitor.query(GetTask(taskId))
    }
}
