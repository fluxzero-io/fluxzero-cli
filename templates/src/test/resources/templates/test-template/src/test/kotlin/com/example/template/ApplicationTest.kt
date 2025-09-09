package com.example.template

import com.example.template.service.TestService
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class ApplicationTest {

    @Test
    fun contextLoads() {
        val service = TestService()
        assert(service.getMessage().contains("com.example.template"))
    }
}