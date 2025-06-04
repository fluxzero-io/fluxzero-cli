package com.example.flux.tasks

import io.fluxcapacitor.javaclient.modeling.Aggregate
import io.fluxcapacitor.javaclient.modeling.EntityId

@Aggregate(searchable = true)
data class Task(
    @EntityId
    val taskId: TaskId,
    val title: String,
    val description: String,
)
