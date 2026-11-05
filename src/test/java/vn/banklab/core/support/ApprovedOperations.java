package vn.banklab.core.support;

import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.net.ssl.SSLSession;
import tools.jackson.databind.json.JsonMapper;

/** Existing financial regression fixtures now use real maker/checker HTTP calls before execution.
 * No approval security is mocked. OperationsIT exercises the public workflow responses directly. */
public final class ApprovedOperations {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private ApprovedOperations() { }
    public static HttpResponse<String> send(HttpClient http, HttpRequest request, SignedTokenServer tokens) throws Exception {
        String path = request.uri().getPath();
        boolean account = path.matches("/api/v1/operations/accounts/[^/]+/(freeze|unfreeze)");
        boolean reversal = path.equals("/api/v1/operations/reversals");
        if (!request.method().equals("POST") || (!account && !reversal)) return http.send(request, HttpResponse.BodyHandlers.ofString());
        String authorization = request.headers().firstValue("Authorization").orElse("");
        if (!authorization.startsWith("Bearer ")) return http.send(request, HttpResponse.BodyHandlers.ofString());
        com.nimbusds.jwt.JWTClaimsSet claims;
        try { claims=SignedJWT.parse(authorization.substring(7)).getJWTClaimsSet(); }
        catch(java.text.ParseException invalid) { return http.send(request,HttpResponse.BodyHandlers.ofString()); }
        var access = (Map<?,?>) claims.getClaim("resource_access");
        var client = access == null ? null : (Map<?,?>) access.get("bank-api");
        if (client == null || !((List<?>) client.get("roles")).contains("OPERATOR")) return http.send(request, HttpResponse.BodyHandlers.ofString());
        String originalBody = body(request); tools.jackson.databind.JsonNode original;
        try {original=JSON.readTree(originalBody);}catch(RuntimeException malformed){return http.send(request,HttpResponse.BodyHandlers.ofString());}
        if(reversal && (request.headers().firstValue("Idempotency-Key").isEmpty()
                || !Set.of("OPERATOR_ERROR","DUPLICATE_PROCESSING").contains(original.path("reason").asString())
                || original.size()!=2 || !original.has("originalJournalId"))) return http.send(request,HttpResponse.BodyHandlers.ofString());
        // Malformed legacy input still exercises legacy HTTP validation.
        if ((account && (!original.has("expectedVersion") || original.path("expectedVersion").asLong()<0)) || !original.has("reason"))
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        String makerSubject = "maker-" + UUID.nameUUIDFromBytes((claims.getIssuer()+"\n"+claims.getSubject()).getBytes(StandardCharsets.UTF_8));
        String maker = tokens.token(b -> b.subject(makerSubject).claim("resource_access",Map.of("bank-api",Map.of("roles",List.of("OPERATOR")))));
        String key = request.headers().firstValue("Idempotency-Key").orElseGet(() -> UUID.randomUUID().toString());
        String type = account ? (path.endsWith("/unfreeze") ? "UNFREEZE" : "FREEZE") : "REVERSAL";
        String target = account ? path.split("/")[5] : original.path("originalJournalId").asString();
        var payload = JSON.createObjectNode().put("type",type).put("targetId",target).put("reason",original.path("reason").asString());
        if (account) payload.put("expectedAccountVersion",original.path("expectedVersion").asLong());
        var prepared = http.send(copy(request,"/api/v1/operations/requests",maker,key,JSON.writeValueAsString(payload)),HttpResponse.BodyHandlers.ofString());
        if (prepared.statusCode()!=201) return prepared;
        String id = JSON.readTree(prepared.body()).path("id").asString();
        var approved = http.send(copy(request,"/api/v1/operations/requests/"+id+"/approve",authorization.substring(7),null,"{\"expectedVersion\":0}"),HttpResponse.BodyHandlers.ofString());
        if (approved.statusCode()!=200) return approved;
        var result = JSON.readTree(approved.body()).path("result");
        String location = account ? "/api/v1/operations/accounts/"+target : "/api/v1/operations/reversals/"+result.path("id").asString();
        return wrap(approved,account ? 200 : 201,JSON.writeValueAsString(result),location);
    }
    private static HttpRequest copy(HttpRequest original,String path,String token,String key,String body) {
        var uri=original.uri(); var b=HttpRequest.newBuilder(URI.create(uri.getScheme()+"://"+uri.getAuthority()+path)).timeout(java.time.Duration.ofSeconds(40))
                .header("Authorization","Bearer "+token).header("Content-Type","application/json");
        original.headers().firstValue("X-Correlation-ID").ifPresent(v -> b.header("X-Correlation-ID",v));
        if(key!=null)b.header("Idempotency-Key",key);
        return b.POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }
    private static String body(HttpRequest request) throws Exception {
        var bytes=new java.io.ByteArrayOutputStream(); var complete=new java.util.concurrent.CompletableFuture<String>();
        request.bodyPublisher().orElseThrow().subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
            public void onSubscribe(java.util.concurrent.Flow.Subscription s){s.request(Long.MAX_VALUE);}
            public void onNext(java.nio.ByteBuffer b){byte[] x=new byte[b.remaining()];b.get(x);bytes.writeBytes(x);}
            public void onError(Throwable e){complete.completeExceptionally(e);}
            public void onComplete(){complete.complete(bytes.toString(StandardCharsets.UTF_8));}
        });return complete.get(5,java.util.concurrent.TimeUnit.SECONDS);
    }
    private static HttpResponse<String> wrap(HttpResponse<String> raw,int code,String text,String location) {
        var headers=new HashMap<>(raw.headers().map()); if(location!=null)headers.put("location",List.of(location));
        return new HttpResponse<>() {
            public int statusCode(){return code;} public String body(){return text;} public HttpRequest request(){return raw.request();}
            public Optional<HttpResponse<String>> previousResponse(){return Optional.empty();}
            public HttpHeaders headers(){return HttpHeaders.of(headers,(k,v)->true);}
            public Optional<SSLSession> sslSession(){return raw.sslSession();} public URI uri(){return raw.uri();}
            public HttpClient.Version version(){return raw.version();}
        };
    }
}
