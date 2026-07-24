package com.example.app.user;

import com.example.app.user.api.GetUsers;
import io.fluxzero.sdk.modeling.Entity;
import io.fluxzero.sdk.test.TestFixture;
import io.fluxzero.sdk.tracking.handling.authentication.UnauthorizedException;
import org.junit.jupiter.api.Test;

class UserTest {

    final TestFixture testFixture = TestFixture.create();

    @Test
    void createViewer() {
        testFixture.whenCommand("/user/create-user.json")
                .expectEvents("/user/create-user.json");
    }


    @Test
    void createUser() {
        testFixture
                .whenCommand("/user/create-user.json")
                .expectEvents("/user/create-user.json");
    }

    @Test
    void createUserTwiceForbidden() {
        testFixture
                .givenCommands("/user/create-user.json")
                .whenCommand("/user/create-user.json")
                .expectExceptionalResult(Entity.ALREADY_EXISTS_EXCEPTION);
    }

    @Test
    void createUserNotAllowedForNonAdmin() {
        testFixture.whenCommandByUser("viewer", "/user/create-admin.json")
                .expectExceptionalResult(UnauthorizedException.class);
    }

    @Test
    void setRole() {
        testFixture
                .givenCommands("/user/create-user.json")
                .whenCommand("/user/make-admin.json")
                .expectEvents("/user/make-admin.json");
    }

    @Test
    void setRoleAsNonAdminNotAllowed() {
        testFixture
                .givenCommands("/user/create-user.json")
                .whenCommandByUser("viewer", "/user/make-admin.json")
                .expectExceptionalResult(UnauthorizedException.class);
    }

    @Test
    void getUsers() {
        testFixture.givenCommands("/user/create-user.json")
                .whenQuery(new GetUsers())
                .expectResult(r -> r.size() == 1);
    }

}