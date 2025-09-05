package com.example.app.gamerental.api.common;

import io.fluxcapacitor.javaclient.tracking.handling.IllegalCommandException;

public interface GameErrors {
    IllegalCommandException alreadyExists = illegalCommandException("Game already exists"),
            notFound = illegalCommandException("Game not found"),
            outOfStock = illegalCommandException("Game is out of stock"),
            notOutYet = illegalCommandException("Game is not out yet"),
            notInPossession = illegalCommandException("Game is not in your possession");

    static IllegalCommandException illegalCommandException(String message) {
        return new IllegalCommandException(message);
    }
}
