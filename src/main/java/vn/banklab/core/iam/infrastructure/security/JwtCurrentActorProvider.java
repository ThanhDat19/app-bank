package vn.banklab.core.iam.infrastructure.security;

import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import vn.banklab.core.iam.application.Actor;
import vn.banklab.core.iam.application.CurrentActorProvider;

@Component
public class JwtCurrentActorProvider implements CurrentActorProvider {

    @Override
    public Actor currentActor() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token) || !token.isAuthenticated()) {
            throw new AuthenticationCredentialsNotFoundException("Verified actor required");
        }
        Set<String> roles = token.getAuthorities().stream().map(authority -> authority.getAuthority())
                .filter(role -> role.equals("ROLE_CUSTOMER") || role.equals("ROLE_OPERATOR"))
                .map(role -> role.substring("ROLE_".length())).collect(Collectors.toUnmodifiableSet());
        return new Actor(token.getToken().getIssuer().toString(), token.getToken().getSubject(), roles);
    }
}
