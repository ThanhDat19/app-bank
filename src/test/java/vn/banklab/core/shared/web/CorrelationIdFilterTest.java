package vn.banklab.core.shared.web;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void cleanContext() {
        MDC.clear();
    }

    @Test
    void bindsValidIdAndRestoresOuterContext() throws Exception {
        MDC.put(CorrelationIdFilter.MDC_KEY, "outer-context");
        var request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "review-002.A_1");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) ->
                assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isEqualTo("review-002.A_1"));
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("review-002.A_1");
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isEqualTo("outer-context");
    }

    @ParameterizedTest
    @MethodSource("invalidIds")
    void generatesIdForAbsentOrUnsafeHeaders(String supplied) throws Exception {
        var request = new MockHttpServletRequest();
        if (supplied != null) {
            request.addHeader(CorrelationIdFilter.HEADER, supplied);
        }
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {});
        String generated = response.getHeader(CorrelationIdFilter.HEADER);
        assertThat(UUID.fromString(generated).toString()).isEqualTo(generated);
        assertThat(generated).isNotEqualTo(supplied);
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    static Stream<String> invalidIds() {
        return Stream.of(null, "", "contains space", "bad\r\nheader", "x".repeat(65), "one,two", "Đạt");
    }

    @Test
    void rejectsRepeatedHeadersAndReusesGeneratedIdDuringErrorDispatch() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "first");
        request.addHeader(CorrelationIdFilter.HEADER, "second");
        var firstResponse = new MockHttpServletResponse();
        filter.doFilter(request, firstResponse, (req, res) -> {});
        String id = firstResponse.getHeader(CorrelationIdFilter.HEADER);
        assertThat(UUID.fromString(id)).isNotNull();
        request.setDispatcherType(DispatcherType.ERROR);
        var errorResponse = new MockHttpServletResponse();
        filter.doFilter(request, errorResponse, (req, res) ->
                assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isEqualTo(id));
        assertThat(errorResponse.getHeader(CorrelationIdFilter.HEADER)).isEqualTo(id);
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void removesRequestContextEvenWhenDownstreamThrows() {
        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (req, res) -> { throw new ServletException("downstream failure"); }))
                .isInstanceOf(ServletException.class);
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }
}
