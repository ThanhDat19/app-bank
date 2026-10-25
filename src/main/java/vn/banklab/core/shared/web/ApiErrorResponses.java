package vn.banklab.core.shared.web;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@ApiResponses({
        @ApiResponse(responseCode = "401", description = "A valid access token is required",
                content = @Content(mediaType = "application/problem+json",
                        schema = @Schema(implementation = ApiProblem.class))),
        @ApiResponse(responseCode = "403", description = "The actor lacks permission",
                content = @Content(mediaType = "application/problem+json",
                        schema = @Schema(implementation = ApiProblem.class))),
        @ApiResponse(responseCode = "default", description = "Request rejected; inspect code and correlationId",
                content = @Content(mediaType = "application/problem+json",
                        schema = @Schema(implementation = ApiProblem.class))),
        @ApiResponse(responseCode = "500", description = "Unexpected failure; no internal details are exposed",
                content = @Content(mediaType = "application/problem+json",
                        schema = @Schema(implementation = ApiProblem.class)))
})
public @interface ApiErrorResponses {
}
