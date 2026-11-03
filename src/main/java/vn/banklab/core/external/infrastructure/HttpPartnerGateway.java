package vn.banklab.core.external.infrastructure;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.*;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import vn.banklab.core.external.application.*;
import vn.banklab.core.shared.application.BusinessException;

@Component
public class HttpPartnerGateway implements PartnerGateway {
    private final ExternalOptions settings;
    private final RestTemplate http;
    private final JsonMapper json=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    public HttpPartnerGateway(ExternalOptions settings){
        this.settings=settings;
        var factory=new SimpleClientHttpRequestFactory(){
            @Override protected void prepareConnection(HttpURLConnection connection,String method)throws IOException{
                super.prepareConnection(connection,method);connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(settings.timeoutMillis());factory.setReadTimeout(settings.timeoutMillis());http=new RestTemplate(factory);
        http.setErrorHandler(new ResponseErrorHandler(){public boolean hasError(org.springframework.http.client.ClientHttpResponse response){return false;}});
    }
    public UUID instance(){
        check();try{
            var root=exchange("/health",HttpMethod.GET,null,null);
            fields(root,Set.of("instanceId","status"));if(!text(root,"status").equals("UP"))throw new IllegalArgumentException();
            return UUID.fromString(text(root,"instanceId"));
        }catch(RuntimeException failure){throw new BusinessException("EXTERNAL_PARTNER_UNAVAILABLE",BusinessException.Kind.DEPENDENCY_UNAVAILABLE);}
    }
    public Result submit(ExternalPayment p){
        return probe(p,HttpMethod.PUT,json.writeValueAsBytes(Map.of("requestHash",p.requestHash(),"amount",p.amount(),"currency","VND","beneficiary",p.beneficiary())));
    }
    public Result query(ExternalPayment p){return probe(p,HttpMethod.GET,null);}
    private Result probe(ExternalPayment p,HttpMethod method,byte[] body){
        check();try{
            var root=exchange("/payments/"+p.id(),method,body,p.partnerInstance());
            fields(root,Set.of("instanceId","paymentId","requestHash","amount","currency","beneficiary","state","version"));
            var version=root.get("version");if(!version.isIntegralNumber()||!version.canConvertToLong())throw new IllegalArgumentException();
            var observation=new Observation(UUID.fromString(text(root,"instanceId")),UUID.fromString(text(root,"paymentId")),text(root,"requestHash"),
                    text(root,"amount"),text(root,"currency"),text(root,"beneficiary"),text(root,"state"),version.asLong());
            return new Result(observation,null);
        }catch(PartnerStatus failure){return new Result(null,switch(failure.status){case 404->"EXTERNAL_PARTNER_NOT_FOUND";case 412->"EXTERNAL_PARTNER_INSTANCE_CHANGED";default->"EXTERNAL_PARTNER_UNAVAILABLE";});
        }catch(RestClientException failure){return new Result(null,"EXTERNAL_PARTNER_UNAVAILABLE");
        }catch(RuntimeException invalid){return new Result(null,"EXTERNAL_PARTNER_INVALID_RESPONSE");}
    }
    private JsonNode exchange(String path,HttpMethod method,byte[] body,UUID instance){
        return http.execute(URI.create("http://127.0.0.1:"+settings.port()+path),method,request->{
            request.getHeaders().setAccept(List.of(MediaType.APPLICATION_JSON));
            if(body!=null){request.getHeaders().setContentType(MediaType.APPLICATION_JSON);request.getHeaders().set("If-Partner-Instance",instance.toString());request.getBody().write(body);}
        },response->{
            if(response.getStatusCode().value()!=200)throw new PartnerStatus(response.getStatusCode().value());
            var content=response.getHeaders().getContentType();if(content==null||!MediaType.APPLICATION_JSON.isCompatibleWith(content))throw new IllegalArgumentException();
            byte[] bytes=response.getBody().readNBytes(8193);if(bytes.length>8192)throw new IllegalArgumentException();return json.readTree(bytes);
        });
    }
    private void check(){
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Partner I/O must be outside a transaction");
        if(!settings.enabled())throw new BusinessException("EXTERNAL_DISABLED",BusinessException.Kind.DEPENDENCY_UNAVAILABLE);
    }
    private static void fields(JsonNode root,Set<String> names){if(root==null||!root.isObject()||!new HashSet<>(root.propertyNames()).equals(names))throw new IllegalArgumentException();}
    private static String text(JsonNode root,String name){var value=root.get(name);if(value==null||!value.isString())throw new IllegalArgumentException();return value.asString();}
    private static final class PartnerStatus extends RuntimeException {final int status;PartnerStatus(int status){this.status=status;}}
}
