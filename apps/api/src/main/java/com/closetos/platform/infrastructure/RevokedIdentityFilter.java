package com.closetos.platform.infrastructure;

import com.closetos.platform.api.IdentityRevocations;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

final class RevokedIdentityFilter extends OncePerRequestFilter {
    private final IdentityRevocations revocations;
    private final MeterRegistry metrics;

    RevokedIdentityFilter(IdentityRevocations revocations, MeterRegistry metrics) {
        this.revocations = revocations;
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token) {
            final boolean revoked;
            try {
                revoked = revocations.revoked(token.getToken().getSubject());
            } catch (DataAccessException failure) {
                metrics.counter("privacy.account.access.unavailable").increment();
                SecurityProblem.write(
                        request,
                        response,
                        503,
                        "ACCOUNT_ACCESS_UNAVAILABLE",
                        "Account access is temporarily unavailable. Try again.");
                return;
            }
            if (revoked) {
                SecurityContextHolder.clearContext();
                metrics.counter("privacy.account.access.revoked").increment();
                SecurityProblem.write(
                        request,
                        response,
                        401,
                        "ACCOUNT_REMOVED",
                        "This account has been removed.");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
