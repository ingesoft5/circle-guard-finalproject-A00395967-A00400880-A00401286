package com.circleguard.auth.controller;

import com.circleguard.auth.client.IdentityClient;
import com.circleguard.auth.service.JwtTokenService;
import com.circleguard.auth.service.CustomUserDetailsService;
import com.circleguard.auth.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.Import;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(LoginController.class)
@Import(SecurityConfig.class)
public class LoginControllerTest {

    private static final String LOGIN_BODY = "{\"username\": \"testuser\", \"password\": \"password123\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthenticationManager authManager;

    @MockBean
    private JwtTokenService jwtService;

    @MockBean
    private IdentityClient identityClient;

    @MockBean
    private CustomUserDetailsService userDetailsService;

    @Test
    void shouldLoginSuccessfullyAndReturnAnonymizedToken() throws Exception {
        String username = "testuser";
        String password = "password123";
        UUID anonymousId = UUID.randomUUID();
        String token = "mock-jwt-token";

        Authentication auth = Mockito.mock(Authentication.class);
        Mockito.when(authManager.authenticate(Mockito.any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(auth);

        Mockito.when(identityClient.getAnonymousId(username)).thenReturn(anonymousId);

        Mockito.when(jwtService.generateToken(Mockito.eq(anonymousId), Mockito.any(Authentication.class)))
                .thenReturn(token);

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\": \"" + username + "\", \"password\": \"" + password + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value(token))
                .andExpect(jsonPath("$.anonymousId").value(anonymousId.toString()))
                .andExpect(jsonPath("$.type").value("Bearer"));
    }

    @Test
    void login_passesSubmittedCredentialsToAuthManagerAndAuthenticatedPrincipalToJwtService() throws Exception {
        UUID anonymousId = UUID.randomUUID();
        Authentication auth = Mockito.mock(Authentication.class);
        Mockito.when(authManager.authenticate(Mockito.any())).thenReturn(auth);
        Mockito.when(identityClient.getAnonymousId("testuser")).thenReturn(anonymousId);
        Mockito.when(jwtService.generateToken(anonymousId, auth)).thenReturn("jwt");

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(LOGIN_BODY))
                .andExpect(status().isOk());

        ArgumentCaptor<Authentication> captor = ArgumentCaptor.forClass(Authentication.class);
        Mockito.verify(authManager).authenticate(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("testuser");
        assertThat(captor.getValue().getCredentials()).isEqualTo("password123");
        // The token must be issued for the anonymous id, using the authentication returned by the manager
        Mockito.verify(jwtService).generateToken(anonymousId, auth);
    }

    @Test
    void login_badCredentials_returns401WithGenericMessageAndIssuesNoToken() throws Exception {
        Mockito.when(authManager.authenticate(Mockito.any()))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(LOGIN_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid username or password"))
                .andExpect(jsonPath("$.token").doesNotExist());

        Mockito.verifyNoInteractions(identityClient, jwtService);
    }

    @Test
    void login_disabledAccount_returns401() throws Exception {
        Mockito.when(authManager.authenticate(Mockito.any()))
                .thenThrow(new DisabledException("User account is disabled"));

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(LOGIN_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid username or password"));
    }

    @Test
    void login_identityServiceFailure_returns500WithErrorMessage() throws Exception {
        Mockito.when(authManager.authenticate(Mockito.any())).thenReturn(Mockito.mock(Authentication.class));
        Mockito.when(identityClient.getAnonymousId("testuser"))
                .thenThrow(new IllegalStateException("identity service down"));

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(LOGIN_BODY))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Internal server error: identity service down"));

        Mockito.verifyNoInteractions(jwtService);
    }

    @Test
    void visitorHandoff_issuesVisitorTokenAndHandoffPayload() throws Exception {
        UUID anonymousId = UUID.randomUUID();
        Mockito.when(jwtService.generateToken(Mockito.eq(anonymousId), Mockito.any())).thenReturn("visitor-jwt");

        mockMvc.perform(post("/api/v1/auth/visitor/handoff")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"anonymousId\": \"" + anonymousId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("visitor-jwt"))
                .andExpect(jsonPath("$.handoffPayload").value("HANDOFF_TOKEN:" + anonymousId + ":visitor-jwt"));

        ArgumentCaptor<Authentication> captor = ArgumentCaptor.forClass(Authentication.class);
        Mockito.verify(jwtService).generateToken(Mockito.eq(anonymousId), captor.capture());
        assertThat(captor.getValue().getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("VISITOR");
    }

    @Test
    void visitorHandoff_missingAnonymousId_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/visitor/handoff")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isBadRequest());

        Mockito.verifyNoInteractions(jwtService);
    }
}
