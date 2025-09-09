package com.example.flux.tasks

import io.fluxcapacitor.javaclient.test.TestFixture
import org.junit.jupiter.api.Test

class TaskTest {
    @Test
    fun `should allow to add a task`() {
        TestFixture
            .create()
            .whenCommand("/tasks/task1-create.json")
            .expectEvents("/tasks/task1-create.json")
    }
}
