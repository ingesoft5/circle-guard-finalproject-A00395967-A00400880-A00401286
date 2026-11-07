package com.circleguard.auth.security;

import com.circleguard.auth.service.JwtTokenService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class JwtAuthenticationFilterTest {

    private static final String SECRET = "unit-test-jwt-secret-with-at-least-32-bytes";

    private JwtAuthenticationFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        filter = new JwtAuthenticationFilter(SECRET);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        chain = mock(FilterChain.class);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static UsernamePasswordAuthenticationToken authWith(String... authorities) {
        return new UsernamePasswordAuthenticationToken("jdoe", null,
                java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
    }

    @Test
    void validToken_populatesSecurityContextWithSubjectAndPermissions() throws Exception {
        UUID anonymousId = UUID.randomUUID();
        String token = new JwtTokenService(SECRET, 60_000L)
                .generateToken(anonymousId, authWith("ROLE_STUDENT", "qr:generate"));
        request.addHeader("Authorization", "Bearer " + token);

        filter.doFilter(request, response, chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth).isNotNull();
        assertThat(auth.getName()).isEqualTo(anonymousId.toString());
        assertThat(auth.isAuthenticated()).isTrue();
        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_STUDENT", "qr:generate");
        verify(chain).doFilter(request, response);
    }

    @Test
    void validTokenWithoutPermissionsClaim_authenticatesWithNoAuthorities() throws Exception {
        String token = Jwts.builder()
                .setSubject("visitor-123")
                .setExpiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), SignatureAlgorithm.HS256)
                .compact();
        request.addHeader("Authorization", "Bearer " + token);

        filter.doFilter(request, response, chain);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertThat(auth.getName()).isEqualTo("visitor-123");
        assertThat(auth.getAuthorities()).isEmpty();
    }

    @Test
    void missingAuthorizationHeader_leavesContextEmptyButContinuesChain() throws Exception {
        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
    }

    @Test
    void nonBearerHeader_isIgnored() throws Exception {
        request.addHeader("Authorization", "Basic amRvZTpzZWNyZXQ=");

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
    }

    @Test
    void malformedToken_clearsExistingContextAndContinuesChain() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(authWith());
        request.addHeader("Authorization", "Bearer not-a-jwt");

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
    }

    @Test
    void tokenSignedWithDifferentKey_isRejected() throws Exception {
        String forged = new JwtTokenService("attacker-secret-that-is-also-32-bytes-long", 60_000L)
                .generateToken(UUID.randomUUID(), authWith("ROLE_ADMIN"));
        request.addHeader("Authorization", "Bearer " + forged);

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
    }

    @Test
    void expiredToken_isRejected() throws Exception {
        String expired = new JwtTokenService(SECRET, -60_000L)
                .generateToken(UUID.randomUUID(), authWith("ROLE_STUDENT"));
        request.addHeader("Authorization", "Bearer " + expired);

        filter.doFilter(request, response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain).doFilter(request, response);
    }
}
