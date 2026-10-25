package vn.banklab.core.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Real public JWKS over HTTP, with ephemeral RSA private keys retained only in this test process. */
public final class SignedTokenServer implements AutoCloseable {
    public static final String AUDIENCE = "bank-api";
    public static final String SUBJECT = "customer-fixture-001";
    private final HttpServer server;
    private final RSAKey signingKey;
    private final RSAKey wrongKey;

    public SignedTokenServer() throws IOException, JOSEException {
        signingKey = new RSAKeyGenerator(2048).keyID("fixture-key").generate();
        wrongKey = new RSAKeyGenerator(2048).keyID("fixture-key").generate();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwks", exchange -> {
            byte[] body = new JWKSet(signingKey.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
    }

    public String issuer() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/issuer";
    }

    public String jwks() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks";
    }

    public JWTClaimsSet.Builder claims() {
        return new JWTClaimsSet.Builder().issuer(issuer()).subject(SUBJECT).audience(AUDIENCE)
                .issueTime(Date.from(Instant.now().minusSeconds(10)))
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("typ", "Bearer")
                .claim("resource_access", Map.of(AUDIENCE, Map.of("roles", List.of("CUSTOMER"))));
    }

    public String token() throws JOSEException {
        return token(builder -> {});
    }

    public String token(Consumer<JWTClaimsSet.Builder> customize) throws JOSEException {
        var builder = claims();
        customize.accept(builder);
        return sign(builder.build(), signingKey, JWSAlgorithm.RS256);
    }

    public String wrongSignature() throws JOSEException {
        return sign(claims().build(), wrongKey, JWSAlgorithm.RS256);
    }

    public String wrongAlgorithm() throws JOSEException {
        return sign(claims().build(), signingKey, JWSAlgorithm.RS512);
    }

    private static String sign(JWTClaimsSet claims, RSAKey key, JWSAlgorithm algorithm) throws JOSEException {
        var jwt = new SignedJWT(new JWSHeader.Builder(algorithm).keyID(key.getKeyID())
                .type(JOSEObjectType.JWT).build(), claims);
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
