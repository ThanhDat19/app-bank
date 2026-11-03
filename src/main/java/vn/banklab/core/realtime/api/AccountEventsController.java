package vn.banklab.core.realtime.api;

import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import vn.banklab.core.realtime.application.AccountEventStreams;
import vn.banklab.core.shared.web.ApiErrorResponses;
import vn.banklab.core.shared.web.CorrelationIdFilter;

@RestController
@ApiErrorResponses
public class AccountEventsController {
    private final AccountEventStreams streams;
    public AccountEventsController(AccountEventStreams streams) { this.streams = streams; }

    @GetMapping(value = "/api/v1/accounts/{accountId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(operationId = "streamOwnedAccountHints", summary = "Stream hints to refresh your account through REST",
            description = "CUSTOMER ownership required; frozen/closed/unprovisioned accounts readable. Every connection starts with resync CONNECTED. Sampled committed changes yield resync STATE_CHANGED; comments are heartbeats. Frames contain accountId/reason only. No durable IDs, replay, per-journal ordering or delivery guarantee: Last-Event-ID is ignored. Always refresh official account/balance/notifications and known command receipts through REST on reconnect; reuse the original idempotency key after a lost command response. Authorization Bearer header only, use fetch streaming in a browser. Stream closes at token exp or bounded lifetime; reconnect with a valid token and backoff. No financial command is run by SSE.",
            parameters = @Parameter(name = "Last-Event-ID", in = ParameterIn.HEADER,
                    description = "Ignored; a new connection always requires REST resync"))
    @ApiResponse(responseCode = "200", description = "SSE resync events and heartbeat comments; no-store",
            content = @Content(mediaType = "text/event-stream", schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "400", description = "Malformed account UUID")
    @ApiResponse(responseCode = "404", description = "ACCOUNT_NOT_FOUND within ownership scope")
    @ApiResponse(responseCode = "409", description = "SSE_CONNECTION_LIMIT: per-instance or issuer/subject capacity reached; retry with backoff")
    public ResponseEntity<SseEmitter> events(@PathVariable UUID accountId, Authentication authentication,
                                              HttpServletRequest request) {
        var emitter = streams.open(accountId, authentication,
                (String) request.getAttribute(CorrelationIdFilter.ATTRIBUTE));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Accel-Buffering", "no")
                .contentType(MediaType.TEXT_EVENT_STREAM).body(emitter);
    }
}
