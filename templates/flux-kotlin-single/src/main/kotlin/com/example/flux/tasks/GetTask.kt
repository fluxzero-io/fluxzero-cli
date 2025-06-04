package com.example.flux.tasks

import io.fluxcapacitor.javaclient.FluxCapacitor
import io.fluxcapacitor.javaclient.tracking.handling.HandleQuery

data class GetTask(
    val id: TaskId,
) {
    @HandleQuery
    fun handle(): Task = FluxCapacitor.loadAggregate(id).get()
}
