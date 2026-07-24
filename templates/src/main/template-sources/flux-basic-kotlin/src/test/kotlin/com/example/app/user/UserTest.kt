package com.example.app.user

import com.example.app.user.api.GetUsers
import com.example.app.user.api.model.UserProfile
import io.fluxzero.sdk.test.TestFixture
import io.fluxzero.sdk.tracking.handling.IllegalCommandException
import io.fluxzero.sdk.tracking.handling.authentication.UnauthorizedException
import org.junit.jupiter.api.Test

class UserTest {

    private val testFixture = TestFixture.create()

    @Test
    fun createViewer() {
        testFixture.whenCommand("/user/create-user.json")
            .expectEvents("/user/create-user.json")
    }

    @Test
    fun createUser() {
        testFixture
            .whenCommand("/user/create-user.json")
            .expectEvents("/user/create-user.json")
    }

    @Test
    fun createUserTwiceForbidden() {
        testFixture
            .givenCommands("/user/create-user.json")
            .whenCommand("/user/create-user.json")
            .expectExceptionalResult(IllegalCommandException::class)
    }

    @Test
    fun createUserNotAllowedForNonAdmin() {
        testFixture.whenCommandByUser("viewer", "/user/create-admin.json")
            .expectExceptionalResult(UnauthorizedException::class)
    }

    @Test
    fun setRole() {
        testFixture
            .givenCommands("/user/create-user.json")
            .whenCommand("/user/make-admin.json")
            .expectEvents("/user/make-admin.json")
    }

    @Test
    fun setRoleAsNonAdminNotAllowed() {
        testFixture
            .givenCommands("/user/create-user.json")
            .whenCommandByUser("viewer", "/user/make-admin.json")
            .expectExceptionalResult(UnauthorizedException::class)
    }

    @Test
    fun getUsers() {
        testFixture.givenCommands("/user/create-user.json")
            .whenQuery(GetUsers())
            .expectResult<List<UserProfile>> { r: List<UserProfile> -> r.size == 1 }
    }

}
