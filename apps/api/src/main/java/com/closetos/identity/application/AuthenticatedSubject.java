package com.closetos.identity.application;

import com.closetos.platform.api.DomainException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

final class AuthenticatedSubject {
    private AuthenticatedSubject() {}

    static Jwt token() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token)
                || token.getToken().getSubject() == null
                || token.getToken().getSubject().isBlank()
                || token.getToken().getSubject().length() > 128) {
            throw new DomainException(401, "AUTHENTICATION", "Sign in to continue.");
        }
        return token.getToken();
    }
}
