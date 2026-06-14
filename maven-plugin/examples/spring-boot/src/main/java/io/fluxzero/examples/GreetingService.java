package io.fluxzero.examples;

import org.springframework.stereotype.Service;

@Service
public class GreetingService {
    public String greeting(String name) {
        return "Boot " + name;
    }
}
