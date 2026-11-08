package com.circleguard.auth.service;

import com.circleguard.auth.model.LocalUser;
import com.circleguard.auth.model.Permission;
import com.circleguard.auth.model.Role;
import com.circleguard.auth.repository.LocalUserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomUserDetailsServiceTest {

    @Mock
    private LocalUserRepository userRepository;

    @InjectMocks
    private CustomUserDetailsService service;

    private static LocalUser user(boolean active, Set<Role> roles) {
        return LocalUser.builder()
                .username("staff_guard")
                .password("$2a$10$hash")
                .isActive(active)
                .roles(roles)
                .build();
    }

    @Test
    void loadUserByUsername_mapsRolesWithPrefixAndPermissionsAsAuthorities() {
        Role role = Role.builder().name("HEALTH_CENTER")
                .permissions(Set.of(
                        Permission.builder().name("alert:send").build(),
                        Permission.builder().name("dashboard:view").build()))
                .build();
        when(userRepository.findByUsername("staff_guard")).thenReturn(Optional.of(user(true, Set.of(role))));

        UserDetails details = service.loadUserByUsername("staff_guard");

        assertThat(details.getUsername()).isEqualTo("staff_guard");
        assertThat(details.getPassword()).isEqualTo("$2a$10$hash");
        assertThat(details.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_HEALTH_CENTER", "alert:send", "dashboard:view");
        verify(userRepository).findByUsername("staff_guard");
    }

    @Test
    void loadUserByUsername_deduplicatesPermissionsSharedAcrossRoles() {
        Permission shared = Permission.builder().name("dashboard:view").build();
        Role admin = Role.builder().name("ADMIN").permissions(Set.of(shared)).build();
        Role health = Role.builder().name("HEALTH_CENTER").permissions(Set.of(shared)).build();
        when(userRepository.findByUsername("staff_guard"))
                .thenReturn(Optional.of(user(true, Set.of(admin, health))));

        UserDetails details = service.loadUserByUsername("staff_guard");

        assertThat(details.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_HEALTH_CENTER", "dashboard:view");
    }

    @Test
    void loadUserByUsername_userWithoutRoles_hasNoAuthorities() {
        when(userRepository.findByUsername("staff_guard")).thenReturn(Optional.of(user(true, Set.of())));

        assertThat(service.loadUserByUsername("staff_guard").getAuthorities()).isEmpty();
    }

    @Test
    void loadUserByUsername_unknownUser_throwsUsernameNotFound() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadUserByUsername("ghost"))
                .isInstanceOf(UsernameNotFoundException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    void loadUserByUsername_inactiveUser_throwsDisabledException() {
        when(userRepository.findByUsername("staff_guard")).thenReturn(Optional.of(user(false, Set.of())));

        assertThatThrownBy(() -> service.loadUserByUsername("staff_guard"))
                .isInstanceOf(DisabledException.class)
                .hasMessage("User account is disabled");
    }
}
