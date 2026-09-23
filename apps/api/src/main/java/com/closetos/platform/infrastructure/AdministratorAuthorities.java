package com.closetos.platform.infrastructure;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

final class AdministratorAuthorities implements Converter<Jwt, Collection<GrantedAuthority>> {
    private final String provider;
    private final JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();

    AdministratorAuthorities(String provider) {
        this.provider = provider;
    }

    @Override
    public Collection<GrantedAuthority> convert(Jwt token) {
        var authorities = new ArrayList<>(scopes.convert(token));
        Object roles = "cognito".equals(provider) ? token.getClaims().get("cognito:groups") : null;
        if ("oidc".equals(provider)
                && token.getClaims().get("realm_access") instanceof Map<?, ?> realm)
            roles = realm.get("roles");
        if (roles instanceof Collection<?> values && values.contains("closetos-admin"))
            authorities.add(new SimpleGrantedAuthority("ROLE_CLOSETOS_ADMIN"));
        return authorities;
    }
}
