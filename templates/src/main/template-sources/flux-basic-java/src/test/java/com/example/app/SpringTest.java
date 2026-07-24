package com.example.app;

import com.example.app.user.api.GetUsers;
import io.fluxzero.sdk.test.TestFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import static org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD;

@SpringBootTest(classes = App.class)
@DirtiesContext(classMode = AFTER_EACH_TEST_METHOD)
class SpringTest {

	@Autowired TestFixture testFixture;

	@AfterEach
	void shutDownFixture() {
		TestFixture.shutDownActiveFixtures();
	}

	@Test
	void createUser() {
		testFixture.whenCommand("/user/create-user.json")
				.expectEvents("/user/create-user.json");
	}

	@Test
	void getUsers() {
		testFixture.givenCommands("/user/create-user.json")
				.whenQuery(new GetUsers())
				.expectResult(r -> r.size() == 1);
	}
}
