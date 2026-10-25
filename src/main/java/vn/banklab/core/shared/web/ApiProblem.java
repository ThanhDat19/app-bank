package vn.banklab.core.shared.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

@Schema(name = "ApiProblem", description = "RFC 9457 error with stable code and correlation ID")
public final class ApiProblem extends ProblemDetail {

    private final String code;
    private final String correlationId;
    private final List<FieldViolation> errors;

    public static ApiProblem unauthenticated(String correlationId) {
        return new ApiProblem(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                "A valid access token is required.", correlationId, List.of());
    }

    public static ApiProblem forbidden(String correlationId) {
        return new ApiProblem(HttpStatus.FORBIDDEN, "ACCESS_DENIED",
                "The current actor is not permitted to perform this operation.", correlationId, List.of());
    }

    ApiProblem(HttpStatusCode status, String code, String detail, String correlationId,
               List<FieldViolation> errors) {
        super(status.value());
        setType(URI.create("urn:banklab:problem:" + code.toLowerCase(Locale.ROOT).replace('_', '-')));
        HttpStatus known = HttpStatus.resolve(status.value());
        setTitle(known == null ? "Request rejected" : known.getReasonPhrase());
        setDetail(detail);
        // An opaque occurrence URI avoids reflecting account identifiers, query strings or tokens.
        setInstance(URI.create("urn:uuid:" + UUID.randomUUID()));
        this.code = code;
        this.correlationId = correlationId;
        this.errors = List.copyOf(errors);
    }

    @Schema(example = "INVALID_REQUEST", requiredMode = Schema.RequiredMode.REQUIRED)
    public String getCode() {
        return code;
    }

    @Schema(example = "review-session-001", requiredMode = Schema.RequiredMode.REQUIRED)
    public String getCorrelationId() {
        return correlationId;
    }

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public List<FieldViolation> getErrors() {
        return errors;
    }
}
