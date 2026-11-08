package com.circleguard.auth.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.ldap.authentication.LdapAuthenticationProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DualChainAuthenticationProviderTest {

    @Mock
    private LdapAuthenticationProvider ldapProvider;

    @Mock
    private DaoAuthenticationProvider localProvider;

    @InjectMocks
    private DualChainAuthenticationProvider provider;

    private final Authentication request = new UsernamePasswordAuthenticationToken("jdoe", "secret");

    @Test
    void authenticate_ldapSucceeds_returnsLdapResultWithoutTouchingLocalDb() {
        Authentication ldapResult = new UsernamePasswordAuthenticationToken("jdoe", null, List.of());
        when(ldapProvider.authenticate(request)).thenReturn(ldapResult);

        Authentication result = provider.authenticate(request);

        assertThat(result).isSameAs(ldapResult);
        verify(localProvider, never()).authenticate(any());
    }

    @Test
    void authenticate_ldapRejects_fallsBackToLocalProviderWithSameRequest() {
        Authentication localResult = new UsernamePasswordAuthenticationToken("jdoe", null, List.of());
        when(ldapProvider.authenticate(request)).thenThrow(new BadCredentialsException("not in LDAP"));
        when(localProvider.authenticate(request)).thenReturn(localResult);

        Authentication result = provider.authenticate(request);

        assertThat(result).isSameAs(localResult);
        verify(ldapProvider).authenticate(request);
        verify(localProvider).authenticate(request);
    }

    @Test
    void authenticate_bothChainsFail_propagatesLocalException() {
        when(ldapProvider.authenticate(request)).thenThrow(new BadCredentialsException("not in LDAP"));
        when(localProvider.authenticate(request)).thenThrow(new BadCredentialsException("bad local password"));

        assertThatThrownBy(() -> provider.authenticate(request))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("bad local password");
    }

    @Test
    void authenticate_ldapNonAuthenticationError_isNotSwallowedAndSkipsLocal() {
        when(ldapProvider.authenticate(request)).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(() -> provider.authenticate(request)).isInstanceOf(IllegalStateException.class);
        verify(localProvider, never()).authenticate(any());
    }

    @Test
    void supports_onlyUsernamePasswordTokens() {
        assertThat(provider.supports(UsernamePasswordAuthenticationToken.class)).isTrue();
        assertThat(provider.supports(TestingAuthenticationToken.class)).isFalse();
    }
}
