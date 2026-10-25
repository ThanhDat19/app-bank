package vn.banklab.core.iam.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.banklab.core.iam.application.CurrentActorProvider;
import vn.banklab.core.shared.web.ApiErrorResponses;

@RestController
@RequestMapping("/api/v1/iam")
@Tag(name = "Identity", description = "Verified actor context")
@ApiErrorResponses
public class CurrentActorController {

    private final CurrentActorProvider actors;

    public CurrentActorController(CurrentActorProvider actors) {
        this.actors = actors;
    }

    @GetMapping("/me")
    @Operation(operationId = "getCurrentActor", summary = "Get the verified current actor",
            description = "Requires bank-api CUSTOMER or OPERATOR role. Actor comes from the verified access token.")
    @ApiResponse(responseCode = "200", description = "Verified actor; never cached")
    public ResponseEntity<CurrentActorResponse> me() {
        var actor = actors.currentActor();
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new CurrentActorResponse(actor.issuer(), actor.subject(), actor.roles().stream().sorted().toList()));
    }
}
