package com.example.app.user;

import com.example.app.authentication.Sender;
import com.example.app.user.api.CreateUser;
import com.example.app.user.api.UserId;
import com.example.app.user.api.model.UserProfile;
import io.fluxzero.sdk.test.TestFixture;
import org.junit.jupiter.api.Test;

import java.util.List;

class UsersEndpointTest {

    final TestFixture testFixture = TestFixture.create(new UsersEndpoint());

    @Test
    void createUser() {
        testFixture.whenPostByUser(Sender.system, "/api/users", "/user/create-user-request.json")
                .expectResult(UserId.class)
                .expectEvents(CreateUser.class);
    }

    @Test
    void getUsers() {
        testFixture.givenPostByUser(Sender.system, "/api/users", "/user/create-user-request.json")
                .whenGetByUser(Sender.system, "/api/users")
                .<List<UserProfile>>expectResult(r -> r.size() == 1);
    }

    @Test
    void openApiDocumentsUsersEndpoint() {
        testFixture.whenGet("/api/openapi.json")
                .expectWebResult(response -> {
                    String payload = response.getPayloadAs(String.class);
                    return response.getStatus() == 200
                           && "application/json".equals(response.getContentType())
                           && payload.contains("User Management API")
                           && payload.contains("\"/api/users\"")
                           && payload.contains("\"createUser\"")
                           && payload.contains("\"getUsers\"");
                });
    }
}
