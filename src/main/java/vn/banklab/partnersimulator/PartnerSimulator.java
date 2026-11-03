package vn.banklab.partnersimulator;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Volatile local partner, never a financial authority. Restart invalidates its instance epoch. */
public final class PartnerSimulator implements AutoCloseable {
    public record Intent(String requestHash,String amount,String currency,String beneficiary) { }
    public record Observation(UUID instanceId,UUID paymentId,String requestHash,String amount,String currency,
                              String beneficiary,String state,long version) { }
    private static final class Payment {
        final Intent intent;
        final List<Observation> history=new ArrayList<>();
        Payment(Intent intent){this.intent=intent;}
    }
    private static final class Rejection extends RuntimeException {
        final int status;final String code;
        Rejection(int status,String code){this.status=status;this.code=code;}
    }
    private final UUID instance=UUID.randomUUID();
    private final Map<UUID,Payment> payments=new HashMap<>();
    private final JsonMapper json=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final HttpServer server;
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(4,4,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(32));
    private final ScheduledExecutorService scheduler=Executors.newSingleThreadScheduledExecutor();
    private final boolean controls;
    private final int capacity;
    private final int completionMillis;

    public PartnerSimulator(int port,boolean controls,int capacity,int completionMillis)throws IOException {
        if(port<0||port>65535||capacity<1||capacity>10000||completionMillis<1||completionMillis>30000)
            throw new IllegalArgumentException("Invalid partner simulator limits");
        this.controls=controls;this.capacity=capacity;this.completionMillis=completionMillis;
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),32);
        server.setExecutor(workers);server.createContext("/",this::handle);
    }
    public void start(){server.start();}
    public int port(){return server.getAddress().getPort();}

    private void handle(HttpExchange exchange)throws IOException {
        try {
            String path=exchange.getRequestURI().getPath();String method=exchange.getRequestMethod();
            int delay=delay(exchange);
            Object response;int status=200;
            if(path.equals("/health")&&method.equals("GET")) {
                response=Map.of("instanceId",instance,"status","UP");
            } else if(path.startsWith("/test/payments/")&&controls) {
                if(!method.equals("POST"))throw reject(405,"METHOD_NOT_ALLOWED");
                UUID id=id(path.substring("/test/payments/".length()));
                var body=body(exchange);fields(body,Set.of("state"));
                response=terminal(id,text(body,"state"));
            } else if(path.startsWith("/payments/")) {
                UUID id=id(path.substring("/payments/".length()));
                if(method.equals("PUT")) {
                    if(exchange.getRequestURI().getRawQuery()!=null)throw reject(400,"INVALID_QUERY");
                    var body=body(exchange);fields(body,Set.of("requestHash","amount","currency","beneficiary"));
                    var intent=new Intent(text(body,"requestHash"),text(body,"amount"),text(body,"currency"),text(body,"beneficiary"));
                    if(!intent.requestHash().matches("[a-f0-9]{64}")||!intent.amount().matches("[1-9][0-9]{0,19}")
                            ||!intent.currency().equals("VND")||!intent.beneficiary().matches("SIM_[A-Z0-9_-]{1,40}"))throw reject(400,"INVALID_INTENT");
                    response=submit(id,intent);
                } else if(method.equals("GET")) {
                    String query=exchange.getRequestURI().getRawQuery();
                    if(query==null)response=read(id,null);
                    else if(controls&&query.matches("version=[12]"))response=read(id,Integer.parseInt(query.substring(8)));
                    else throw reject(400,"INVALID_QUERY");
                } else throw reject(405,"METHOD_NOT_ALLOWED");
            } else throw reject(404,"NOT_FOUND");
            // Capture immutable observation before delaying: an older response may arrive after a newer one.
            if(delay>0)Thread.sleep(delay);
            send(exchange,status,response);
        } catch(Rejection rejected){send(exchange,rejected.status,Map.of("code",rejected.code,"instanceId",instance));
        } catch(InterruptedException interrupted){Thread.currentThread().interrupt();
        } catch(IOException disconnected){/* A lost response never rolls back an accepted partner operation. */
        } catch(RuntimeException failure){send(exchange,400,Map.of("code","INVALID_REQUEST","instanceId",instance));
        } finally{exchange.close();}
    }

    private synchronized Observation submit(UUID id,Intent intent) {
        var existing=payments.get(id);
        if(existing!=null){if(!existing.intent.equals(intent))throw reject(409,"IDEMPOTENCY_CONFLICT");return latest(existing);}
        if(payments.size()>=capacity)throw reject(503,"SIMULATOR_CAPACITY");
        var payment=new Payment(intent);payment.history.add(observe(id,intent,"PENDING",1));payments.put(id,payment);
        if(!controls)scheduler.schedule(()->terminal(id,"SUCCEEDED"),completionMillis,TimeUnit.MILLISECONDS);
        return latest(payment);
    }
    private synchronized Observation terminal(UUID id,String state) {
        if(!Set.of("SUCCEEDED","FAILED").contains(state))throw reject(400,"INVALID_STATE");
        var payment=payments.get(id);if(payment==null)throw reject(404,"PAYMENT_NOT_FOUND");
        var previous=latest(payment);
        if(!previous.state().equals("PENDING")){
            if(!previous.state().equals(state))throw reject(409,"TERMINAL_CONFLICT");return previous;
        }
        var next=observe(id,payment.intent,state,2);payment.history.add(next);return next;
    }
    private synchronized Observation read(UUID id,Integer version) {
        var payment=payments.get(id);if(payment==null)throw reject(404,"PAYMENT_NOT_FOUND");
        if(version==null)return latest(payment);
        if(version>payment.history.size())throw reject(404,"VERSION_NOT_FOUND");return payment.history.get(version-1);
    }
    private Observation observe(UUID id,Intent i,String state,long version){return new Observation(instance,id,i.requestHash(),i.amount(),i.currency(),i.beneficiary(),state,version);}
    private static Observation latest(Payment payment){return payment.history.getLast();}
    private JsonNode body(HttpExchange exchange)throws IOException {
        String type=exchange.getRequestHeaders().getFirst("Content-Type");
        if(type==null||!type.split(";",2)[0].trim().equalsIgnoreCase("application/json"))throw reject(415,"UNSUPPORTED_MEDIA_TYPE");
        byte[] bytes=exchange.getRequestBody().readNBytes(4097);if(bytes.length>4096)throw reject(413,"BODY_TOO_LARGE");
        try{return json.readTree(bytes);}catch(RuntimeException invalid){throw reject(400,"INVALID_JSON");}
    }
    private static void fields(JsonNode body,Set<String> expected){
        if(body==null||!body.isObject()||!new HashSet<>(body.propertyNames()).equals(expected))throw reject(400,"INVALID_FIELDS");
    }
    private static String text(JsonNode body,String name){var node=body.get(name);if(node==null||!node.isString())throw reject(400,"INVALID_FIELDS");return node.asString();}
    private static UUID id(String text){try{UUID id=UUID.fromString(text);if(!id.toString().equalsIgnoreCase(text))throw new IllegalArgumentException();return id;}catch(IllegalArgumentException invalid){throw reject(400,"INVALID_PAYMENT_ID");}}
    private int delay(HttpExchange exchange){
        var values=exchange.getRequestHeaders().get("X-Simulator-Delay-Millis");if(values==null)return 0;
        if(!controls)throw reject(400,"TEST_CONTROLS_DISABLED");
        if(values.size()!=1||!values.getFirst().matches("[0-9]{1,4}"))throw reject(400,"INVALID_DELAY");
        int delay=Integer.parseInt(values.getFirst());if(delay>2000)throw reject(400,"INVALID_DELAY");return delay;
    }
    private void send(HttpExchange exchange,int status,Object body)throws IOException {
        byte[] bytes=json.writeValueAsBytes(body);exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control","no-store");exchange.sendResponseHeaders(status,bytes.length);
        try(var output=exchange.getResponseBody()){output.write(bytes);}
    }
    private static Rejection reject(int status,String code){return new Rejection(status,code);}
    @Override public void close(){server.stop(0);scheduler.shutdownNow();workers.shutdownNow();}
}
