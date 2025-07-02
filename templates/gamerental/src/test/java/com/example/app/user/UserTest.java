package com.example.app.user;

import io.fluxcapacitor.javaclient.test.TestFixture;
import io.fluxcapacitor.javaclient.tracking.handling.authentication.UnauthorizedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

class UserTest {

    final TestFixture testFixture = TestFixture.create();

    @Test
    void createViewer() {
        testFixture.whenCommand("/user/create-customer.json")
                .expectEvents("/user/create-customer.json");
    }


    @Test
    void createUserAllowedAsEditor() {
        testFixture
                .givenCommands("/user/create-admin.json")
                .whenCommandByUser("admin", "/user/create-customer.json")
                .expectEvents("/user/create-customer.json");
    }

    @Test
    void createUserNotAllowedAsViewer() {
        testFixture.whenCommandByUser("viewer", "/user/create-admin.json")
                .expectExceptionalResult(UnauthorizedException.class);
    }

    @Test
    void getUsers() {
        testFixture.givenCommands("/user/create-customer.json")
                .whenQuery(new GetUsers())
                .expectResult(r -> r.size() == 1);
    }

    @Nested
    class UsersEndpointTests {

        @BeforeEach
        void setUp() {
            testFixture.registerHandlers(new UsersEndpoint());
        }

        @Test
        void createUser() {
            testFixture.whenPost("/users", "/user/new-user-details.json")
                    .expectResult(UserId.class)
                    .expectEvents(CreateUser.class);
        }

        @Test
        void getUsers() {
            testFixture.givenPost("/users", "/user/new-user-details.json")
                    .whenGet("/users")
                    .<List<UserProfile>>expectResult(r -> r.size() == 1);
        }
    }
}