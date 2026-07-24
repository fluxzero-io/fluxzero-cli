package com.example.app.user.api

import io.fluxzero.sdk.tracking.handling.authentication.UnauthorizedException

interface UserErrors {
    companion object {
        val unauthorized: UnauthorizedException = UnauthorizedException("Not authorized to perform this operation")
    }
}
