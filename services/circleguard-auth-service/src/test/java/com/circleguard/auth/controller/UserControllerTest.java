package com.circleguard.auth.controller;

import com.circleguard.auth.model.LocalUser;
import com.circleguard.auth.repository.LocalUserRepository;
import com.circleguard.auth.security.SecurityConfig;
import com.circleguard.auth.service.CustomUserDetailsService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
@Import(SecurityConfig.class)
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private LocalUserRepository localUserRepository;

    @MockBean
    private AuthenticationManager authManager;

    @MockBean
    private CustomUserDetailsService userDetailsService;

    @Test
    void getUsersByPermission_returnsUsernameAndEmailOfMatchingUsers() throws Exception {
        Mockito.when(localUserRepository.findUsersByPermissionName("alert:receive_priority")).thenReturn(List.of(
                LocalUser.builder().username("health_staff").email("salud@circleguard.edu").build(),
                LocalUser.builder().username("admin").email("admin@circleguard.edu").build()));

        mockMvc.perform(get("/api/v1/users/permissions/alert:receive_priority"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].username").value("health_staff"))
                .andExpect(jsonPath("$[0].email").value("salud@circleguard.edu"))
                .andExpect(jsonPath("$[1].username").value("admin"));

        Mockito.verify(localUserRepository).findUsersByPermissionName("alert:receive_priority");
    }

    @Test
    void getUsersByPermission_userWithoutEmail_returnsEmptyEmailInsteadOfNull() throws Exception {
        Mockito.when(localUserRepository.findUsersByPermissionName("dashboard:view")).thenReturn(List.of(
                LocalUser.builder().username("no_mail").email(null).build()));

        mockMvc.perform(get("/api/v1/users/permissions/dashboard:view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].username").value("no_mail"))
                .andExpect(jsonPath("$[0].email").value(""));
    }

    @Test
    void getUsersByPermission_noMatches_returnsEmptyArray() throws Exception {
        Mockito.when(localUserRepository.findUsersByPermissionName("unknown")).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/users/permissions/unknown"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }
}
