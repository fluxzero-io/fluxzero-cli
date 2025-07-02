package com.example.app.gamerental.announcer;

import com.example.app.gamerental.api.GameUpdate;
import io.fluxcapacitor.javaclient.test.TestFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class GameAnnouncerTest {

    final TestFixture testFixture = TestFixture.createAsync(GameUpdate.class, FlakyGameAnnouncer.class);

    @Test
    void noFlakeAfterFirstGame() {
        testFixture.whenCommand("/game/register-game.json")
                .expectEvents(GameAnnouncement.class)
                .expectNoErrors();
    }

    @Test
    void errorOnSecondAnnouncement() {
        testFixture.givenCommands("/game/register-game.json")
                .whenCommand("/game/register-other-game.json")
                .expectNoEventsLike(GameAnnouncement.class)
                .expectError();
    }

    @Nested
    class CorrectingGameAnnouncerTests {

        @BeforeEach
        void setUp() {
            testFixture.registerHandlers(new CorrectingGameAnnouncer());
        }

        @Test
        void noFlakeAfterFirstGame() {
            testFixture.whenCommand("/game/register-game.json")
                    .expectEvents(GameAnnouncement.class)
                    .expectNoErrors();
        }

        @Test
        void errorCorrectedOnSecondAnnouncement() {
            testFixture.givenCommands("/game/register-game.json")
                    .whenCommand("/game/register-other-game.json")
                    .expectError()
                    .expectEvents(GameAnnouncement.class)
                    .<GameAnnouncement>expectEvent(a -> a.message().equals("Correct that game announcement"));
        }
    }
}