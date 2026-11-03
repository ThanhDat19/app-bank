package vn.banklab.partnersimulator;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.JarFile;
import org.junit.jupiter.api.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class PartnerSimulatorIT {
    private PartnerSimulator server;
    private final HttpClient http=HttpClient.newHttpClient();
    private final JsonMapper json=JsonMapper.builder().build();
    @BeforeEach void start()throws Exception{server=new PartnerSimulator(0,true,16,100);server.start();}
    @AfterEach void close(){server.close();}
    private String intent(String amount){return "{\"requestHash\":\""+"a".repeat(64)+"\",\"amount\":\""+amount+"\",\"currency\":\"VND\",\"beneficiary\":\"SIM_DESTINATION_1\"}";}
    private HttpRequest request(int port,String method,String path,String body,String delay,long timeout){
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofMillis(timeout));
        if(body!=null)b.header("Content-Type","application/json");if(delay!=null)b.header("X-Simulator-Delay-Millis",delay);
        return b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build();
    }
    private HttpResponse<String> call(String method,String path,String body)throws Exception{return http.send(request(server.port(),method,path,body,null,5000),HttpResponse.BodyHandlers.ofString());}
    private JsonNode tree(HttpResponse<String> r){return json.readTree(r.body());}
    private String path(UUID id){return "/payments/"+id;}
    private HttpResponse<String> finish(UUID id,String state)throws Exception{return call("POST","/test/payments/"+id,"{\"state\":\""+state+"\"}");}

    @Test void exactAmountsIdempotencyAndPayloadConflictsRetainImmutableIntent()throws Exception{
        UUID id=UUID.randomUUID();String path=path(id),body=intent("99999999999999999999");
        var original=call("PUT",path,body);assertThat(original.statusCode()).isEqualTo(200);assertThat(tree(original).path("amount").asString()).isEqualTo("99999999999999999999");
        assertThat(tree(original).path("state").asString()).isEqualTo("PENDING");assertThat(tree(original).path("version").asLong()).isEqualTo(1);
        assertThat(original.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(call("PUT",path,body).body()).isEqualTo(original.body());
        assertThat(call("PUT",path,intent("1")).statusCode()).isEqualTo(409);
        assertThat(call("GET",path,null).body()).isEqualTo(original.body());
    }
    @Test void concurrentSameIdRequestsCreateOneOperationAndNeverChangePayload()throws Exception{
        UUID id=UUID.randomUUID();String body=intent("100");
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var start=new CountDownLatch(1);var tasks=new ArrayList<Future<HttpResponse<String>>>();
            for(int i=0;i<16;i++)tasks.add(pool.submit(()->{start.await();return call("PUT",path(id),body);}));
            start.countDown();String expected=null;
            for(var f:tasks){var r=f.get(10,TimeUnit.SECONDS);assertThat(r.statusCode()).isEqualTo(200);if(expected==null)expected=r.body();else assertThat(r.body()).isEqualTo(expected);}
        }
    }
    @Test void terminalStatesAreStickyMonotonicAndDuplicateObservationsAreStable()throws Exception{
        for(String state:List.of("SUCCEEDED","FAILED")){
            UUID id=UUID.randomUUID();call("PUT",path(id),intent("1"));var terminal=finish(id,state);
            assertThat(tree(terminal).path("version").asLong()).isEqualTo(2);assertThat(tree(terminal).path("state").asString()).isEqualTo(state);
            assertThat(finish(id,state).body()).isEqualTo(terminal.body());
            assertThat(finish(id,state.equals("SUCCEEDED")?"FAILED":"SUCCEEDED").statusCode()).isEqualTo(409);
            assertThat(call("PUT",path(id),intent("1")).body()).isEqualTo(terminal.body());
            assertThat(tree(call("GET",path(id)+"?version=1",null)).path("state").asString()).isEqualTo("PENDING");
            assertThat(call("GET",path(id),null).body()).isEqualTo(terminal.body());
        }
    }
    @Test void delayedResponseCanArriveAfterANewerTerminalObservation()throws Exception{
        UUID id=UUID.randomUUID();var delayed=http.sendAsync(request(server.port(),"PUT",path(id),intent("7"),"1500",5000),HttpResponse.BodyHandlers.ofString());
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);HttpResponse<String> found;
        do{found=call("GET",path(id),null);if(found.statusCode()==200)break;Thread.sleep(20);}while(System.nanoTime()<until);
        assertThat(found.statusCode()).isEqualTo(200);var newer=finish(id,"SUCCEEDED");
        var older=delayed.get(5,TimeUnit.SECONDS);assertThat(tree(older).path("version").asLong()).isEqualTo(1);
        assertThat(tree(newer).path("version").asLong()).isEqualTo(2);assertThat(tree(older).path("instanceId")).isEqualTo(tree(newer).path("instanceId"));
        assertThat(call("GET",path(id),null).body()).isEqualTo(newer.body());
    }
    @Test void lostSubmitResponseDoesNotUndoAcceptanceAndQueryCanRecover()throws Exception{
        call("GET","/health",null);UUID id=UUID.randomUUID();
        assertThatThrownBy(()->http.send(request(server.port(),"PUT",path(id),intent("9"),"1500",500),HttpResponse.BodyHandlers.ofString())).isInstanceOf(HttpTimeoutException.class);
        var query=call("GET",path(id),null);assertThat(query.statusCode()).isEqualTo(200);
        assertThat(call("PUT",path(id),intent("9")).body()).isEqualTo(query.body());
    }
    @Test void strictInputAndBodyBoundsProduceSanitizedErrors()throws Exception{
        UUID id=UUID.randomUUID();
        for(String amount:List.of("0","01","-1","1.5","100000000000000000000"))assertThat(call("PUT",path(id),intent(amount)).statusCode()).isEqualTo(400);
        for(String body:List.of(intent("1")+" {}",intent("1").replace("\"1\"","1"),intent("1").replace("SIM_DESTINATION_1","real-customer-name"),intent("1").replace("{","{\"extra\":\"secret\","),"{\"amount\":\"1\",\"amount\":\"2\"}","{")){
            var r=call("PUT",path(id),body);assertThat(r.statusCode()).isEqualTo(400);assertThat(r.body()).doesNotContain("secret","Exception","stack");
        }
        assertThat(call("PUT",path(id)," ".repeat(4097)).statusCode()).isEqualTo(413);
        assertThat(call("GET","/payments/not-a-uuid",null).statusCode()).isEqualTo(400);
        assertThat(call("DELETE",path(id),null).statusCode()).isEqualTo(405);
        assertThat(call("GET",path(id),null).statusCode()).isEqualTo(404);
    }
    @Test void boundedCapacityPreservesReplayAndRestartChangesInstanceWithMissingHistory()throws Exception{
        try(var small=new PartnerSimulator(0,true,1,100)){
            small.start();UUID id=UUID.randomUUID();var original=http.send(request(small.port(),"PUT",path(id),intent("1"),null,3000),HttpResponse.BodyHandlers.ofString());
            var denied=http.send(request(small.port(),"PUT",path(UUID.randomUUID()),intent("1"),null,3000),HttpResponse.BodyHandlers.ofString());assertThat(denied.statusCode()).isEqualTo(503);
            assertThat(http.send(request(small.port(),"PUT",path(id),intent("1"),null,3000),HttpResponse.BodyHandlers.ofString()).body()).isEqualTo(original.body());
            small.close();
            try(var restarted=new PartnerSimulator(0,true,1,100)){
                restarted.start();var other=http.send(request(restarted.port(),"GET",path(id),null,null,3000),HttpResponse.BodyHandlers.ofString());
                assertThat(other.statusCode()).isEqualTo(404);assertThat(tree(other).path("instanceId")).isNotEqualTo(tree(original).path("instanceId"));
            }
        }
    }
    @Test void actualPackagedProcessDefaultsToAutoSuccessAndTestControlsAreDisabled()throws Exception{
        Path artifact=Path.of(System.getProperty("bank.partner-jar"));try(var jar=new JarFile(artifact.toFile())){assertThat(jar.getManifest().getMainAttributes().getValue("Start-Class")).isEqualTo("vn.banklab.partnersimulator.PartnerSimulatorMain");}
        int port;try(var socket=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){port=socket.getLocalPort();}
        String executable=System.getProperty("os.name").startsWith("Windows")?"java.exe":"java";
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",executable).toString(),"-jar",artifact.toAbsolutePath().toString());
        builder.environment().put("PARTNER_SIMULATOR_PORT",String.valueOf(port));builder.environment().remove("PARTNER_SIMULATOR_TEST_CONTROLS");
        for(String name:List.of("DB_URL","DB_USERNAME","DB_PASSWORD","OIDC_ISSUER_URI","OIDC_JWK_SET_URI","OIDC_AUDIENCE"))builder.environment().remove(name);
        builder.redirectErrorStream(true).redirectOutput(Path.of("target","partner-simulator-process.log").toFile());var process=builder.start();
        try{
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);boolean ready=false;
            while(System.nanoTime()<until){assertThat(process.isAlive()).isTrue();try{ready=http.send(request(port,"GET","/health",null,null,500),HttpResponse.BodyHandlers.ofString()).statusCode()==200;if(ready)break;}catch(java.io.IOException starting){}Thread.sleep(100);}
            assertThat(ready).isTrue();UUID id=UUID.randomUUID();assertThat(http.send(request(port,"PUT",path(id),intent("2"),null,3000),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
            assertThat(http.send(request(port,"POST","/test/payments/"+id,"{\"state\":\"FAILED\"}",null,3000),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
            assertThat(http.send(request(port,"GET",path(id),null,"1",3000),HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
            until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);JsonNode state;
            do{state=tree(http.send(request(port,"GET",path(id),null,null,3000),HttpResponse.BodyHandlers.ofString()));if(state.path("state").asString().equals("SUCCEEDED"))break;Thread.sleep(50);}while(System.nanoTime()<until);
            assertThat(state.path("state").asString()).isEqualTo("SUCCEEDED");assertThat(state.path("version").asLong()).isEqualTo(2);
        }finally{process.destroy();if(!process.waitFor(5,TimeUnit.SECONDS))process.destroyForcibly().waitFor(5,TimeUnit.SECONDS);}
    }
}
