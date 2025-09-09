package com.example.flux.tasks

import io.fluxcapacitor.javaclient.FluxCapacitor
import io.fluxcapacitor.javaclient.persisting.eventsourcing.Apply
import io.fluxcapacitor.javaclient.tracking.handling.HandleCommand

data class AddTask(
    val taskId: TaskId,
    val title: String,
    val description: String,
) {
    @HandleCommand
    fun handle(): Task = FluxCapacitor.loadAggregate(taskId).assertAndApply(this).get()

    @Apply
    fun apply() = Task(taskId, title, description)
}
