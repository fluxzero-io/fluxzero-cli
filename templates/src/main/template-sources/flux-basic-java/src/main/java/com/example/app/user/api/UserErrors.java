package com.example.app.user.api;

import io.fluxzero.sdk.tracking.handling.authentication.UnauthorizedException;

public interface UserErrors {
    UnauthorizedException unauthorized = new UnauthorizedException("Not authorized to perform this operation");
}
