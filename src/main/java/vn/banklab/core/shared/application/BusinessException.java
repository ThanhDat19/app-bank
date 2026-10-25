package vn.banklab.core.shared.application;

import java.util.Objects;

/** Public codes are developer-defined constants, never customer input or exception messages. */
public final class BusinessException extends RuntimeException {

    public enum Kind { NOT_FOUND, CONFLICT, RULE_VIOLATION }

    private final String code;
    private final Kind kind;

    public BusinessException(String code, Kind kind) {
        super("Business operation rejected");
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{1,63}")) {
            throw new IllegalArgumentException("Business code must be a stable uppercase identifier");
        }
        this.code = code;
        this.kind = Objects.requireNonNull(kind);
    }

    public String getCode() {
        return code;
    }

    public Kind getKind() {
        return kind;
    }
}
