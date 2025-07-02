package com.example.app.gamerental;

import com.example.app.gamerental.api.FindGames;
import com.example.app.gamerental.api.GetGame;
import com.example.app.gamerental.api.GetGameStats;
import com.example.app.gamerental.api.RegisterGame;
import com.example.app.gamerental.api.RentGame;
import com.example.app.gamerental.api.common.Game;
import com.example.app.gamerental.api.common.GameErrors;
import com.example.app.gamerental.api.common.GameId;
import io.fluxcapacitor.common.api.search.FacetStats;
import io.fluxcapacitor.javaclient.test.TestFixture;
import io.fluxcapacitor.javaclient.tracking.handling.validation.ValidationException;
import io.fluxcapacitor.javaclient.web.HttpRequestMethod;
import io.fluxcapacitor.javaclient.web.WebRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

class GameTest {

    private final TestFixture testFixture = TestFixture.create();

    @Test
    void registerGame() {
        testFixture.whenCommand("/game/register-game.json")
                .expectEvents("/game/register-game.json");
    }

    @Test
    void registerInvalidGame() {
        testFixture.whenCommand("/game/register-invalid-game.json")
                .expectExceptionalResult(ValidationException.class);
    }

    @Test
    void registerGameTwiceFails() {
        testFixture.givenCommands("/game/register-game.json")
                .whenCommand("/game/register-game.json")
                .expectExceptionalResult(GameErrors.alreadyExists);
    }

    @Test
    void rentGame() {
        testFixture.givenCommands("/game/register-game.json")
                .whenCommand("/game/rent-game.json")
                .expectEvents("/game/rent-game.json");
    }

    @Test
    void rentGame_unknown() {
        testFixture
                .whenCommand("/game/rent-game.json")
                .expectExceptionalResult(GameErrors.notFound);
    }

    @Test
    void rentGame_outOfStock() {
        testFixture.givenCommands("/game/register-game.json", "/game/rent-game.json")
                .whenCommand("/game/rent-game.json")
                .expectExceptionalResult(GameErrors.outOfStock);
    }

    @Test
    void rentGame_notOutYet() {
        testFixture.atFixedTime(Instant.parse("2025-04-15T00:00:00Z"))
                .givenCommands("/game/register-game.json")
                .whenCommand("/game/rent-game.json")
                .expectExceptionalResult(GameErrors.notOutYet);
    }

    @Test
    void returnGame() {
        testFixture.givenCommands("/game/register-game.json", "/game/rent-game.json")
                .whenCommand("/game/return-game.json")
                .expectEvents("/game/return-game.json");
    }

    @Test
    void returnGame_notRented() {
        testFixture.givenCommands("/game/register-game.json")
                .whenCommand("/game/return-game.json")
                .expectExceptionalResult(GameErrors.notInPossession);
    }

    @Test
    void returnGame_invalidRenter() {
        testFixture
                .givenCommands("/user/create-customer.json")
                .givenCommands("/game/register-game.json", "/game/rent-game.json")
                .whenCommandByUser("customer", "/game/return-game.json")
                .expectExceptionalResult(GameErrors.notInPossession);
    }

    @Nested
    class GameQueries {
        @Test
        void getSingleGame() {
            testFixture.givenCommands("/game/register-game.json")
                    .whenQuery(new GetGame("myGame"))
                    .expectResult(Game.class);
        }

        @Test
        void getUnknownGame() {
            testFixture.givenCommands("/game/register-game.json")
                    .whenQuery(new GetGame("unknown"))
                    .expectNoResult();
        }

        @Test
        void findGames() {
            testFixture.givenCommands("/game/register-games.json")
                    .whenQuery(new FindGames("cryptographic mysteries"))
                    .expectResult(r -> r.size() == 1);
        }

        @Test
        void gameStats_stock() {
            testFixture.givenCommands("/game/register-game.json", "/game/register-games.json")
                    .whenQuery(new GetGameStats("stock"))
                    .expectResult(r -> r.size() == 1
                                       && r.contains(new FacetStats("stock", "1", 21)))
                    .andThen()
                    .givenCommands("/game/rent-game.json")
                    .whenQuery(new GetGameStats("stock"))
                    .expectResult(r -> r.contains(new FacetStats("stock", "0", 1)))
                    .expectResult(r -> r.contains(new FacetStats("stock", "1", 20)));
        }
    }

    @Nested
    class GameApiTests {

        @BeforeEach
        void setUp() {
            testFixture.registerHandlers(new GameApi());
        }

        @Test
        void registerGame() {
            testFixture.whenPost("/games", "/game/game-details.json")
                    .expectResult(GameId.class).expectEvents(RegisterGame.class);
        }

        @Test
        void getGames() {
            testFixture.givenPost("/games", "/game/game-details.json")
                    .whenGet("/games")
                    .<List<Game>>expectResult(r -> r.size() == 1);
        }

        @Test
        void rentGame() {
            testFixture.whenPost("/games", "/game/game-details.json")
                    .andThen()
                    .whenPost("/games/{gameId}/rent")
                    .expectEvents(RentGame.class);
        }
    }

    @Nested
    class GameWebsocketTests {
        @BeforeEach
        void setUp() {
            testFixture.registerHandlers(GameApi.class, GameSocket.class)
                    .givenCommands("/game/register-games.json"); //adds 20 games
        }

        @Test
        void sendWholeCatalogOnOpen() {
            testFixture.whenWebRequest(openSocket())
                    .expectWebResponse(r -> r.getPayload() instanceof List<?> gamesList
                                            && gamesList.size() == 20);
        }

        @Test
        void sendAdditionalGamesWhenRegistered() {
            testFixture.givenWebRequest(openSocket())
                    .whenCommand("/game/register-game.json")
                    .expectWebResponse(r -> r.getPayload() instanceof List<?> newGames
                                            && newGames.size() == 1);
        }

        static WebRequest openSocket() {
            return WebRequest.builder().url("/games").method(HttpRequestMethod.WS_OPEN).build();
        }
    }
}