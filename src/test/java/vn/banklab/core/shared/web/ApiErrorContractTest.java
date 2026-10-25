package vn.banklab.core.shared.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.banklab.core.shared.application.BusinessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ApiErrorContractTest.ProbeController.class)
@Import({ApiErrorContractTest.ProbeController.class, ApiExceptionHandler.class, CorrelationIdFilter.class})
class ApiErrorContractTest {

    @Autowired
    MockMvc mvc;

    @Test
    void malformedJsonUsesSafeProblemContractAndSameCorrelationId() throws Exception {
        mvc.perform(post("/test/contracts").header(CorrelationIdFilter.HEADER, "review-002")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"secret\":\"do-not-expose"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(CorrelationIdFilter.HEADER, "review-002"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.type").value("urn:banklab:problem:invalid-request"))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.instance", containsString("urn:uuid:")))
                .andExpect(jsonPath("$.correlationId").value("review-002"))
                .andExpect(jsonPath("$.errors").isEmpty())
                .andExpect(content().string(not(containsString("do-not-expose"))));
    }

    @Test
    void validationListsOnlyFieldAndConstraint() throws Exception {
        mvc.perform(post("/test/contracts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("displayName"))
                .andExpect(jsonPath("$.errors[0].code").value("NotBlank"))
                .andExpect(jsonPath("$.errors[0].rejectedValue").doesNotExist())
                .andExpect(jsonPath("$.errors[0].message").doesNotExist());
    }

    @Test
    void unknownRouteDoesNotReflectQuerySecrets() throws Exception {
        mvc.perform(get("/api/v1/unknown-account").queryParam("token", "secret-query"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(content().string(not(containsString("secret-query"))));
    }

    @Test
    void methodNotAllowedKeepsAllowHeader() throws Exception {
        mvc.perform(put("/test/contracts"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("POST")))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void unsupportedContentTypeUses415() throws Exception {
        mvc.perform(post("/test/contracts").contentType(MediaType.TEXT_PLAIN).content("secret-value"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
                .andExpect(content().string(not(containsString("secret-value"))));
    }

    @Test
    void invalidTypedParameterUses400WithoutRejectedValue() throws Exception {
        mvc.perform(get("/test/contracts/search").queryParam("size", "private-value"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(content().string(not(containsString("private-value"))));
    }

    @Test
    void parameterConstraintUsesSameValidationContract() throws Exception {
        mvc.perform(get("/test/contracts/search").queryParam("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("size"))
                .andExpect(jsonPath("$.errors[0].code").value("Min"));
    }

    @Test
    void unsupportedResponseFormatUses406() throws Exception {
        mvc.perform(get("/test/contracts/search").queryParam("size", "1").accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable())
                .andExpect(jsonPath("$.code").value("NOT_ACCEPTABLE"));
    }

    @Test
    void unexpectedFailureDoesNotExposeExceptionMessage() throws Exception {
        mvc.perform(get("/test/contracts/unexpected").header(CorrelationIdFilter.HEADER, "failed-request"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.correlationId").value("failed-request"))
                .andExpect(content().string(not(containsString("password=private"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));
    }

    @Test
    void doesNotAppendProblemJsonToAnAlreadyCommittedResponse() throws Exception {
        mvc.perform(get("/test/contracts/committed"))
                .andExpect(status().isOk())
                .andExpect(content().string("already-started"));
    }

    @ParameterizedTest
    @CsvSource({"NOT_FOUND,404", "CONFLICT,409", "RULE_VIOLATION,422"})
    void businessFailureKeepsStableCode(String kind, int expectedStatus) throws Exception {
        mvc.perform(get("/test/contracts/business/" + kind))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.code").value("TEST_RULE"));
    }

    @Test
    void independentRequestsDoNotReuseGeneratedCorrelationIds() throws Exception {
        String first = mvc.perform(get("/test/contracts/search").param("size", "1"))
                .andExpect(status().isOk()).andReturn().getResponse().getHeader(CorrelationIdFilter.HEADER);
        String second = mvc.perform(get("/test/contracts/search").param("size", "2"))
                .andExpect(status().isOk()).andReturn().getResponse().getHeader(CorrelationIdFilter.HEADER);
        assertThat(first).isNotBlank().isNotEqualTo(second);
    }

    @TestComponent
    @RestController
    @RequestMapping("/test/contracts")
    static class ProbeController {

        @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
        String validate(@Valid @RequestBody ProbeRequest request) {
            return "accepted";
        }

        @GetMapping("/search")
        int search(@Min(1) @RequestParam int size) {
            return size;
        }

        @GetMapping("/unexpected")
        String unexpected() {
            throw new IllegalStateException("password=private");
        }

        @GetMapping("/committed")
        void committed(HttpServletResponse response) throws IOException {
            response.getWriter().write("already-started");
            response.flushBuffer();
            throw new IllegalStateException("private committed failure");
        }

        @GetMapping("/business/{kind}")
        String business(@PathVariable BusinessException.Kind kind) {
            throw new BusinessException("TEST_RULE", kind);
        }
    }

    record ProbeRequest(@NotBlank String displayName) {
    }
}
