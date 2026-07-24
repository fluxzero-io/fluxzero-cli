package com.example.app.user.api

import com.example.app.user.api.model.UserProfile
import io.fluxzero.sdk.Fluxzero
import io.fluxzero.sdk.common.serialization.FilterContent
import io.fluxzero.sdk.tracking.handling.HandleQuery
import io.fluxzero.sdk.tracking.handling.Request

data class GetUsers(val dummy: Unit = Unit) : Request<List<UserProfile>> {
    @HandleQuery
    @FilterContent
    fun handle(): List<UserProfile> {
        return Fluxzero.search(UserProfile::class.java).fetchAll()
    }
}
