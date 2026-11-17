package io.fluxzero.tools.account;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

final class AccountCrypto {
    private static final Base64.Encoder BASE64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODE = Base64.getUrlDecoder();
    private static final SecureRandom RANDOM = new SecureRandom();

    record ProofKey(String privateKey, String x, String y) {
        @Override public String toString() { return "ProofKey[redacted]"; }
        Map<String, String> jwk() { return Map.of("kty", "EC", "crv", "P-256", "x", x, "y", y); }
        String thumbprint() {
            return hash("{\"crv\":\"P-256\",\"kty\":\"EC\",\"x\":\"" + x + "\",\"y\":\"" + y + "\"}");
        }
    }

    static ProofKey key() {
        try {
            var generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            var pair = generator.generateKeyPair();
            var key = (ECPublicKey) pair.getPublic();
            return new ProofKey(BASE64.encodeToString(pair.getPrivate().getEncoded()),
                    BASE64.encodeToString(coordinate(key.getW().getAffineX())),
                    BASE64.encodeToString(coordinate(key.getW().getAffineY())));
        } catch (Exception e) { throw new IllegalStateException("Cannot create the account proof key"); }
    }

    static String proof(ProofKey key, URI target, String method, String nonce) {
        try {
            Map<String, Object> claims = new LinkedHashMap<>();
            claims.put("jti", UUID.randomUUID().toString());
            claims.put("htm", method);
            claims.put("htu", new URI(target.getScheme(), target.getAuthority(), target.getPath(), null, null).toString());
            claims.put("iat", Instant.now().getEpochSecond());
            if (nonce != null) claims.put("nonce", nonce);
            String unsigned = json(Map.of("typ", "dpop+jwt", "alg", "ES256", "jwk", key.jwk())) + "." + json(claims);
            Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
            signer.initSign(KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(DECODE.decode(key.privateKey()))));
            signer.update(unsigned.getBytes(StandardCharsets.US_ASCII));
            return unsigned + "." + BASE64.encodeToString(signer.sign());
        } catch (Exception e) { throw new IllegalStateException("Cannot create an account request proof"); }
    }

    static JsonNode verifyIdentity(String token, JsonNode jwks, AccountConfiguration config, String nonce, String subject) {
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3 || token.length() > 32768) throw invalid();
            JsonNode header = AccountHttp.JSON.readTree(DECODE.decode(parts[0]));
            JsonNode claims = AccountHttp.JSON.readTree(DECODE.decode(parts[1]));
            String algorithm = header.path("alg").asText();
            if (!header.path("crit").isMissingNode()) throw invalid();
            String kid = header.path("kid").asText("");
            java.security.PublicKey verifiedKey = null;
            for (JsonNode candidate : jwks.path("keys")) {
                if (!kid.equals(candidate.path("kid").asText(""))) continue;
                if (candidate.has("use") && !"sig".equals(candidate.path("use").asText())) continue;
                if (candidate.has("alg") && !algorithm.equals(candidate.path("alg").asText())) continue;
                java.security.PublicKey key;
                Signature verifier;
                if ("RS256".equals(algorithm) && "RSA".equals(candidate.path("kty").asText())) {
                    BigInteger n = integer(candidate, "n");
                    if (n.bitLength() < 2048) continue;
                    key = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, integer(candidate, "e")));
                    verifier = Signature.getInstance("SHA256withRSA");
                } else if ("ES256".equals(algorithm) && "EC".equals(candidate.path("kty").asText())
                        && "P-256".equals(candidate.path("crv").asText())) {
                    var parameters = java.security.AlgorithmParameters.getInstance("EC");
                    parameters.init(new ECGenParameterSpec("secp256r1"));
                    key = KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(
                            new ECPoint(integer(candidate, "x"), integer(candidate, "y")), parameters.getParameterSpec(ECParameterSpec.class)));
                    verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
                } else continue;
                verifier.initVerify(key);
                verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
                if (verifier.verify(DECODE.decode(parts[2]))) { verifiedKey = key; break; }
            }
            long now = Instant.now().getEpochSecond();
            JsonNode audience = claims.path("aud");
            boolean matches = audience.isTextual() ? config.clientId().equals(audience.asText())
                    : audience.isArray() && java.util.stream.StreamSupport.stream(audience.spliterator(), false)
                            .anyMatch(value -> config.clientId().equals(value.asText()));
            if (verifiedKey == null || !config.issuer().toString().equals(claims.path("iss").asText()) || !matches
                    || !claims.path("exp").isIntegralNumber() || claims.path("exp").asLong() <= now
                    || !claims.path("iat").isIntegralNumber() || claims.path("iat").asLong() > now + 30
                    || !claims.path("sub").isTextual() || claims.path("sub").asText().isBlank()
                    || (claims.has("nbf") && (!claims.path("nbf").isIntegralNumber() || claims.path("nbf").asLong() > now + 30))
                    || (claims.has("azp") && !config.clientId().equals(claims.path("azp").asText()))
                    || (audience.isArray() && audience.size() > 1 && !config.clientId().equals(claims.path("azp").asText()))
                    || nonce != null && !constantEquals(nonce, claims.path("nonce").asText())
                    || subject != null && !constantEquals(subject, claims.path("sub").asText())) throw invalid();
            return claims;
        } catch (AccountException e) { throw e; }
        catch (Exception e) { throw invalid(); }
    }

    private static BigInteger integer(JsonNode value, String field) { return new BigInteger(1, DECODE.decode(value.path(field).asText())); }
    private static byte[] coordinate(BigInteger value) {
        byte[] source = value.toByteArray();
        byte[] result = new byte[32];
        System.arraycopy(source, Math.max(0, source.length - 32), result, Math.max(0, 32 - source.length), Math.min(32, source.length));
        return result;
    }
    static String random() { byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes); return BASE64.encodeToString(bytes); }
    static String hash(String value) {
        try { return BASE64.encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    static boolean constantEquals(String a, String b) { return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8)); }
    private static String json(Object value) throws Exception { return BASE64.encodeToString(AccountHttp.JSON.writeValueAsBytes(value)); }
    private static AccountException invalid() { return new AccountException("invalid_identity", "Your sign-in could not be verified. Please try signing in again."); }
}
