package com.example.app.user

import com.example.app.authentication.RequiresRole
import com.example.app.user.api.CreateUser
import com.example.app.user.api.GetUsers
import com.example.app.user.api.UserId
import com.example.app.user.api.model.UserDetails
import com.example.app.user.api.model.UserProfile
import io.fluxzero.sdk.Fluxzero
import io.fluxzero.sdk.web.ApiDoc
import io.fluxzero.sdk.web.HandleGet
import io.fluxzero.sdk.web.HandlePost
import io.fluxzero.sdk.web.Path
import org.springframework.stereotype.Component

@Component
@RequiresRole
@Path("/api")
@ApiDoc(tags = ["Users"], description = "User profile endpoints.")
class UsersEndpoint {

    @HandlePost("/users")
    @ApiDoc(
        summary = "Create user",
        operationId = "createUser",
        description = "Creates a user profile and returns the generated user id.",
    )
    fun createUser(@ApiDoc(description = "User profile details for the new user.") details: UserDetails): UserId {
        val userId = Fluxzero.generateId(UserId::class.java)
        Fluxzero.sendCommandAndWait<CreateUser>(CreateUser(userId, details, null))
        return userId
    }

    @HandleGet("/users")
    @ApiDoc(
        summary = "List users",
        operationId = "getUsers",
        description = "Returns the user profiles visible to the current sender.",
    )
    fun getUsers(): List<UserProfile> {
        return Fluxzero.queryAndWait(GetUsers())
    }
}
