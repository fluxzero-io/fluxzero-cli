package com.example.flux

import com.example.flux.tasks.AddTask
import com.example.flux.tasks.TaskId
import io.fluxcapacitor.javaclient.FluxCapacitor
import io.fluxcapacitor.javaclient.configuration.FluxCapacitorBuilder
import io.fluxcapacitor.javaclient.configuration.client.Client
import io.fluxcapacitor.javaclient.configuration.client.WebSocketClient
import io.fluxcapacitor.javaclient.configuration.spring.FluxCapacitorSpringConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.ApplicationListener
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.event.ContextRefreshedEvent
import java.time.LocalDateTime

@SpringBootApplication
@Import(FluxCapacitorSpringConfig::class)
class FluxApplication {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    @Bean
    fun fluxCapacitorClient(
        @Value("\${FLUX_BASE_URL:ws://localhost:8888}") fluxBaseUrl: String,
    ): Client =
        WebSocketClient.newInstance(
            WebSocketClient.ClientConfig
                .builder()
                .name("flux-application")
                .serviceBaseUrl(fluxBaseUrl)
                .build(),
        )

    @Bean
    fun applicationInit(fluxCapacitor: FluxCapacitor): ApplicationListener<ContextRefreshedEvent> =
        ApplicationListener<ContextRefreshedEvent> { event ->
            // Do things upon application startup
            log.debug { "Flux application started at ${LocalDateTime.now(fluxCapacitor.clock())}" }

            // Initially create a demonstration task for testing purposes; this should be removed upon production
            FluxCapacitor.sendCommand<Any>(AddTask(TaskId("1"), "First task", "Example"))
        }

    @Autowired
    fun configure(builder: FluxCapacitorBuilder) {
        // override flux capacitor options with custom options
        builder.let {}
    }
}

fun main(args: Array<String>) {
    runApplication<FluxApplication>(args = args)
}
