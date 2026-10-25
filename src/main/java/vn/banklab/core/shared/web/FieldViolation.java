package vn.banklab.core.shared.web;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Invalid field and constraint; rejected values are never returned")
public record FieldViolation(
        @Schema(example = "displayName") String field,
        @Schema(example = "NotBlank") String code) {
}
