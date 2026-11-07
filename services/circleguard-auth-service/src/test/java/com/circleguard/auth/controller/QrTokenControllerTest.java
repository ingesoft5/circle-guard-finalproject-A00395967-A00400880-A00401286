package com.circleguard.auth.controller;

import com.circleguard.auth.security.SecurityConfig;
import com.circleguard.auth.service.CustomUserDetailsService;
import com.circleguard.auth.service.QrTokenService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(QrTokenController.class)
@Import(SecurityConfig.class)
class QrTokenControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private QrTokenService qrService;

    @MockBean
    private AuthenticationManager authManager;

    @MockBean
    private CustomUserDetailsService userDetailsService;

    @Test
    void generate_authenticatedUser_returnsQrTokenForPrincipalAnonymousId() throws Exception {
        UUID anonymousId = UUID.randomUUID();
        Mockito.when(qrService.generateQrToken(anonymousId)).thenReturn("qr-token");

        mockMvc.perform(get("/api/v1/auth/qr/generate").with(user(anonymousId.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.qrToken").value("qr-token"))
                .andExpect(jsonPath("$.expiresIn").value("60"));

        Mockito.verify(qrService).generateQrToken(anonymousId);
    }

    @Test
    void generate_unauthenticated_isRejectedAndNoTokenIsIssued() throws Exception {
        mockMvc.perform(get("/api/v1/auth/qr/generate"))
                .andExpect(status().isForbidden());

        Mockito.verifyNoInteractions(qrService);
    }
}
