package vn.banklab.core.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class SecurityProblemHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final JsonMapper json;

    public SecurityProblemHandler(JsonMapper json) {
        this.json = json;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        write(request, response, true);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
            throws IOException {
        write(request, response, false);
    }

    private void write(HttpServletRequest request, HttpServletResponse response, boolean unauthenticated)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        String id = (String) request.getAttribute(CorrelationIdFilter.ATTRIBUTE);
        if (id == null) {
            id = UUID.randomUUID().toString();
            request.setAttribute(CorrelationIdFilter.ATTRIBUTE, id);
        }
        var problem = unauthenticated ? ApiProblem.unauthenticated(id) : ApiProblem.forbidden(id);
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(CorrelationIdFilter.HEADER, id);
        if (unauthenticated) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        json.writeValue(response.getOutputStream(), problem);
    }
}
