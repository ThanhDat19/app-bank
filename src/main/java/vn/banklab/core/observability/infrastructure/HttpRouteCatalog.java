package vn.banklab.core.observability.infrastructure;

import java.util.HashSet;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@Component
public class HttpRouteCatalog {
    public static final String UNMAPPED = "UNMAPPED";
    private final ObjectProvider<RequestMappingHandlerMapping> mappings;
    private volatile Set<String> known;
    public HttpRouteCatalog(@Qualifier("requestMappingHandlerMapping") ObjectProvider<RequestMappingHandlerMapping> mappings) { this.mappings = mappings; }
    public String safe(Object candidate) { return candidate != null && routes().contains(candidate.toString()) ? candidate.toString() : UNMAPPED; }
    private synchronized Set<String> routes() {
        if (known == null) {
            var routes = new HashSet<String>(Set.of("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness", "/actuator/prometheus"));
            mappings.getObject().getHandlerMethods().keySet().forEach(mapping -> routes.addAll(mapping.getPatternValues()));
            if (routes.size() > 256) throw new IllegalStateException("Review telemetry route bound before adding more routes");
            known = Set.copyOf(routes);
        }
        return known;
    }
}
