package com.example.template.service

import org.springframework.stereotype.Service

@Service
class TestService {
    fun getMessage(): String {
        return "Hello from com.example.template"
    }
}