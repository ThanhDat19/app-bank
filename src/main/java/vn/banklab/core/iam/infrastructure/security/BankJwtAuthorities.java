package vn.banklab.core.iam.infrastructure.security;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

public final class BankJwtAuthorities implements Converter<Jwt, Collection<GrantedAuthority>> {

    private static final Set<String> ALLOWED = Set.of("CUSTOMER", "OPERATOR");
    private final String client;

    public BankJwtAuthorities(String client) {
        this.client = client;
    }

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Object access = jwt.getClaims().get("resource_access");
        if (!(access instanceof Map<?, ?> clients) || !(clients.get(client) instanceof Map<?, ?> bank)
                || !(bank.get("roles") instanceof Collection<?> roles)) {
            return List.of();
        }
        return roles.stream().filter(String.class::isInstance).map(String.class::cast)
                .filter(ALLOWED::contains).distinct().sorted()
                .<GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList();
    }
}
