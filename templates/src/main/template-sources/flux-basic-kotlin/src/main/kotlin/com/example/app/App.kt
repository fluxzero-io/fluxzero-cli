package com.example.app

import io.fluxzero.common.serialization.RegisterType
import org.slf4j.LoggerFactory
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication

@SpringBootApplication
@RegisterType(root = "com.example.app")
class App {
    companion object {
        private val log = LoggerFactory.getLogger(App::class.java)

        @JvmStatic
        fun main(args: Array<String>) {
            SpringApplication.run(App::class.java, *args)
            log.info("Application started successfully")
        }
    }
}
