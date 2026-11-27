package io.fluxzero.tools.account;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class AccountCryptoTest {
    private static KeyPair key;
    private static ObjectNode jwks;
    private final AccountConfiguration configuration = new AccountConfiguration("test", URI.create("https://identity.example.test"),
            "native-client", "", URI.create("https://identity.example.test/account"), Path.of("unused"), true, Duration.ofSeconds(10));

    @BeforeAll static void keys() throws Exception {
        key = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        var rsa = (RSAPublicKey) key.getPublic();
        jwks = AccountHttp.JSON.valueToTree(Map.of("keys", List.of(Map.of("kty", "RSA", "kid", "test", "alg", "RS256",
                "n", unsigned(rsa.getModulus()), "e", unsigned(rsa.getPublicExponent())))));
    }

    @Test void acceptsOnlySignedIdentityForTheExpectedIssuerClientNonceAndSubject() throws Exception {
        var claims = claims();
        assertEquals("subject", AccountCrypto.verifyIdentity(signed(claims), jwks, configuration, "nonce", "subject").path("sub").asText());
        List<Consumer<ObjectNode>> invalid = List.of(
                c -> c.put("iss", "https://another.example.test"), c -> c.put("aud", "other-client"),
                c -> c.putArray("aud").add("native-client").add("other-client"),
                c -> c.put("azp", "other-client"), c -> c.put("nonce", "other-nonce"), c -> c.put("sub", "other-subject"),
                c -> c.put("sub", 123), c -> c.put("exp", 1), c -> c.put("exp", "99999999999"),
                c -> c.put("iat", Instant.now().plusSeconds(60).getEpochSecond()),
                c -> c.put("nbf", Instant.now().plusSeconds(60).getEpochSecond()), c -> c.put("nbf", "tomorrow"));
        for (var mutation : invalid) {
            var value = claims(); mutation.accept(value);
            assertEquals("invalid_identity", assertThrows(AccountException.class,
                    () -> AccountCrypto.verifyIdentity(signed(value), jwks, configuration, "nonce", "subject")).code());
        }
        String token = signed(claims());
        String tampered = token.substring(0, token.lastIndexOf('.') + 1) + Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[256]);
        assertThrows(AccountException.class, () -> AccountCrypto.verifyIdentity(tampered, jwks, configuration, "nonce", "subject"));
        assertThrows(AccountException.class, () -> AccountCrypto.verifyIdentity(token, AccountHttp.JSON.createObjectNode(), configuration, "nonce", "subject"));
    }

    @Test void proofIsSignedByItsPublishedKeyAndBoundToMethodEndpointAndNonce() throws Exception {
        var proofKey = AccountCrypto.key();
        String[] parts = AccountCrypto.proof(proofKey, URI.create("https://identity.example.test/token?ignored=query"), "POST", "server-nonce").split("\\.");
        var header = AccountHttp.JSON.readTree(Base64.getUrlDecoder().decode(parts[0]));
        var claims = AccountHttp.JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertEquals("dpop+jwt", header.path("typ").asText());
        assertEquals("https://identity.example.test/token", claims.path("htu").asText());
        assertEquals("POST", claims.path("htm").asText());
        assertEquals("server-nonce", claims.path("nonce").asText());
        assertFalse(claims.path("jti").asText().isBlank());
        assertTrue(Math.abs(Instant.now().getEpochSecond() - claims.path("iat").asLong()) <= 30);
        assertFalse(header.has("privateKey"));
        assertFalse(header.path("jwk").has("d"));
        var parameters = java.security.AlgorithmParameters.getInstance("EC");
        parameters.init(new java.security.spec.ECGenParameterSpec("secp256r1"));
        var publicKey = java.security.KeyFactory.getInstance("EC").generatePublic(new java.security.spec.ECPublicKeySpec(
                new java.security.spec.ECPoint(new BigInteger(1, Base64.getUrlDecoder().decode(proofKey.x())),
                        new BigInteger(1, Base64.getUrlDecoder().decode(proofKey.y()))),
                parameters.getParameterSpec(java.security.spec.ECParameterSpec.class)));
        var verifier = Signature.getInstance("SHA256withECDSAinP1363Format"); verifier.initVerify(publicKey);
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertTrue(verifier.verify(Base64.getUrlDecoder().decode(parts[2])));
    }

    private ObjectNode claims() {
        return AccountHttp.JSON.valueToTree(Map.of("iss", configuration.issuer().toString(), "aud", configuration.clientId(),
                "sub", "subject", "nonce", "nonce", "iat", Instant.now().getEpochSecond(), "exp", Instant.now().plusSeconds(300).getEpochSecond()));
    }
    private String signed(ObjectNode claims) throws Exception {
        String unsigned = base64(AccountHttp.JSON.writeValueAsBytes(Map.of("alg", "RS256", "kid", "test"))) + "." + base64(AccountHttp.JSON.writeValueAsBytes(claims));
        var signer = Signature.getInstance("SHA256withRSA"); signer.initSign(key.getPrivate());
        signer.update(unsigned.getBytes(StandardCharsets.US_ASCII));
        return unsigned + "." + base64(signer.sign());
    }
    private static String unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        return base64(bytes[0] == 0 ? java.util.Arrays.copyOfRange(bytes, 1, bytes.length) : bytes);
    }
    private static String base64(byte[] bytes) {return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
}
