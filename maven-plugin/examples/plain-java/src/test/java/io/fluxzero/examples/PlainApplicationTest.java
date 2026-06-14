package io.fluxzero.examples;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlainApplicationTest {
    @Test
    void formatsMessage() {
        assertEquals("Hello Fluxzero", PlainApplication.message("fluxzero"));
    }
}
