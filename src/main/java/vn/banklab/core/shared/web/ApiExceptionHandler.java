package vn.banklab.core.shared.web;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import vn.banklab.core.shared.application.BusinessException;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        boolean validationFailure = exception instanceof MethodArgumentNotValidException
                || (exception instanceof HandlerMethodValidationException validation && !validation.isForReturnValue());
        List<FieldViolation> errors = violations(exception);
        String code = validationFailure ? "VALIDATION_FAILED" : switch (status.value()) {
            case 400 -> "INVALID_REQUEST";
            case 404 -> "RESOURCE_NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 406 -> "NOT_ACCEPTABLE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            default -> status.is5xxServerError() ? "INTERNAL_ERROR" : "REQUEST_REJECTED";
        };
        return response(request, headers, status, code, detail(code), errors);
    }

    private static List<FieldViolation> violations(Exception exception) {
        Stream<FieldViolation> violations;
        if (exception instanceof MethodArgumentNotValidException validation) {
            violations = validation.getBindingResult().getAllErrors().stream().map(error ->
                    new FieldViolation(error instanceof FieldError field ? field.getField() : "$", constraintCode(error)));
        } else if (exception instanceof HandlerMethodValidationException validation && !validation.isForReturnValue()) {
            violations = Stream.concat(validation.getParameterValidationResults().stream().flatMap(result -> {
                if (result instanceof ParameterErrors fields) {
                    return fields.getAllErrors().stream().map(error -> new FieldViolation(
                            error instanceof FieldError field ? field.getField() : "$", constraintCode(error)));
                }
                String name = result.getMethodParameter().getParameterName();
                String field = name == null ? "argument" + result.getMethodParameter().getParameterIndex() : name;
                return result.getResolvableErrors().stream().map(error -> new FieldViolation(field, constraintCode(error)));
            }), validation.getCrossParameterValidationResults().stream()
                    .map(error -> new FieldViolation("$", constraintCode(error))));
        } else {
            return List.of();
        }
        return violations.distinct().sorted(Comparator.comparing(FieldViolation::field)
                .thenComparing(FieldViolation::code)).toList();
    }

    private static String constraintCode(MessageSourceResolvable error) {
        String[] codes = error.getCodes();
        return codes != null && codes.length > 0 && codes[codes.length - 1].matches("[A-Za-z][A-Za-z0-9]*")
                ? codes[codes.length - 1] : "Invalid";
    }

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<Object> handleBusiness(BusinessException exception, WebRequest request) {
        HttpStatus status = switch (exception.getKind()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case RULE_VIOLATION -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
        return response(request, HttpHeaders.EMPTY, status, exception.getCode(),
                "The operation cannot be completed for the current resource state.", List.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception exception, WebRequest request) {
        // Exception messages/arguments can carry SQL, secrets or customer data.
        log.error("Unhandled API failure type={} correlationId={}",
                exception.getClass().getName(), correlationId(request));
        return response(request, HttpHeaders.EMPTY, HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR", detail("INTERNAL_ERROR"), List.of());
    }

    private ResponseEntity<Object> response(WebRequest request, HttpHeaders original,
            HttpStatusCode status, String code, String detail, List<FieldViolation> errors) {
        if (request instanceof ServletWebRequest servlet && servlet.getResponse() != null
                && servlet.getResponse().isCommitted()) {
            return null;
        }
        String id = correlationId(request);
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(original);
        headers.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        headers.set(CorrelationIdFilter.HEADER, id);
        headers.setCacheControl("no-store");
        return new ResponseEntity<>(new ApiProblem(status, code, detail, id, errors), headers, status);
    }

    private static String correlationId(WebRequest request) {
        Object id = request.getAttribute(CorrelationIdFilter.ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (id instanceof String value) {
            return value;
        }
        String generated = UUID.randomUUID().toString();
        request.setAttribute(CorrelationIdFilter.ATTRIBUTE, generated, RequestAttributes.SCOPE_REQUEST);
        return generated;
    }

    private static String detail(String code) {
        return switch (code) {
            case "INVALID_REQUEST" -> "The request could not be read or contains invalid parameters.";
            case "VALIDATION_FAILED" -> "One or more request fields are invalid.";
            case "RESOURCE_NOT_FOUND" -> "The requested resource was not found.";
            case "METHOD_NOT_ALLOWED" -> "The HTTP method is not supported for this resource.";
            case "NOT_ACCEPTABLE" -> "The requested response format is not supported.";
            case "UNSUPPORTED_MEDIA_TYPE" -> "The request content type is not supported.";
            case "INTERNAL_ERROR" -> "An unexpected error occurred. Contact support with the correlation ID.";
            default -> "The request could not be processed.";
        };
    }
}
