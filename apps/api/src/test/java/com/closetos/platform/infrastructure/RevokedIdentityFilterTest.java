package com.closetos.platform.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.closetos.platform.api.IdentityRevocations;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class RevokedIdentityFilterTest {
    @Test
    void aFailedRevocationLookupCannotFallThroughToTheController() throws Exception {
        var revocations = mock(IdentityRevocations.class);
        var chain = mock(FilterChain.class);
        var metrics = new SimpleMeterRegistry();
        when(revocations.revoked("owner"))
                .thenThrow(new DataAccessResourceFailureException("private diagnostics"));
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                new JwtAuthenticationToken(
                        Jwt.withTokenValue("test-token")
                                .header("alg", "RS256")
                                .subject("owner")
                                .build()));
        SecurityContextHolder.setContext(context);
        try {
            var response = new MockHttpServletResponse();
            new RevokedIdentityFilter(revocations, metrics)
                    .doFilter(new MockHttpServletRequest(), response, chain);
            assertThat(response.getStatus()).isEqualTo(503);
            assertThat(response.getContentAsString())
                    .contains("ACCOUNT_ACCESS_UNAVAILABLE")
                    .doesNotContain("private diagnostics");
            verify(chain, never()).doFilter(any(), any());
            assertThat(metrics.counter("privacy.account.access.unavailable").count()).isEqualTo(1);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
