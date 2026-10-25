package vn.banklab.core.iam.application;

import java.util.Set;

/** Identity is the issuer/subject pair, never an unverified caller-supplied header. */
public record Actor(String issuer, String subject, Set<String> roles) {
    public Actor {
        roles = Set.copyOf(roles);
    }
}
