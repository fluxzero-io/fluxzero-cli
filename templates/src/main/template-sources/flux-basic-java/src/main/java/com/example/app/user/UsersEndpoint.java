package com.example.app.user;

import com.example.app.user.api.CreateUser;
import com.example.app.user.api.GetUsers;
import com.example.app.user.api.UserId;
import com.example.app.user.api.model.UserDetails;
import com.example.app.user.api.model.UserProfile;
import io.fluxzero.sdk.Fluxzero;
import io.fluxzero.sdk.web.ApiDoc;
import io.fluxzero.sdk.web.HandleGet;
import io.fluxzero.sdk.web.HandlePost;
import io.fluxzero.sdk.web.Path;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Path("/api")
@ApiDoc(tags = "Users", description = "User profile endpoints.")
public class UsersEndpoint {
    @HandlePost("/users")
    @ApiDoc(
            summary = "Create user",
            operationId = "createUser",
            description = "Creates a user profile and returns the generated user id.")
    UserId createUser(@ApiDoc(description = "User profile details for the new user.") UserDetails details) {
        var userId = Fluxzero.generateId(UserId.class);
        Fluxzero.sendCommandAndWait(new CreateUser(userId, details, null));
        return userId;
    }

    @HandleGet("/users")
    @ApiDoc(
            summary = "List users",
            operationId = "getUsers",
            description = "Returns the user profiles visible to the current sender.")
    List<UserProfile> getUsers() {
        return Fluxzero.queryAndWait(new GetUsers());
    }

}
