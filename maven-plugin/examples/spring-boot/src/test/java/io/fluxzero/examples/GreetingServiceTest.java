package io.fluxzero.examples;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GreetingServiceTest {
    @Test
    void formatsGreeting() {
        assertThat(new GreetingService().greeting("fluxzero")).isEqualTo("Boot fluxzero");
    }
}
