package com.example.flux.tasks

import io.fluxcapacitor.javaclient.modeling.Id

data class TaskId(
    val taskId: String,
) : Id<Task>(taskId)
