package com.example.app

import com.example.app.user.api.GetUsers
import com.example.app.user.api.model.UserProfile
import io.fluxzero.sdk.test.TestFixture
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.annotation.DirtiesContext

import org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD

@SpringBootTest(classes = [App::class])
@DirtiesContext(classMode = AFTER_EACH_TEST_METHOD)
class SpringTest {

    @Autowired
    lateinit var testFixture: TestFixture

    @AfterEach
    fun shutDownFixture() {
        TestFixture.shutDownActiveFixtures()
    }

    @Test
    fun createUser() {
        testFixture.whenCommand("/user/create-user.json")
            .expectEvents("/user/create-user.json")
    }

    @Test
    fun getUsers() {
        testFixture.givenCommands("/user/create-user.json")
            .whenQuery(GetUsers())
            .expectResult { r: List<UserProfile> -> r.size == 1 }
    }
}
