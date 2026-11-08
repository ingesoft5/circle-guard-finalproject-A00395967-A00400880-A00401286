package com.circleguard.identity.service;

import com.circleguard.identity.model.IdentityMapping;
import com.circleguard.identity.repository.IdentityMappingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IdentityVaultServiceTest {

    private static final String REAL_IDENTITY = "student@circleguard.edu";

    @Mock
    private IdentityMappingRepository repository;

    @InjectMocks
    private IdentityVaultService service;

    @BeforeEach
    void setUp() {
        // @Value is not processed outside Spring; use the same value as test application.yml.
        ReflectionTestUtils.setField(service, "hashSalt", "12345678");
    }

    @Test
    void getOrCreateAnonymousId_existingMapping_returnsSameIdWithoutSaving() {
        UUID existingId = UUID.randomUUID();
        when(repository.findByIdentityHash(anyString()))
                .thenReturn(Optional.of(IdentityMapping.builder().anonymousId(existingId).build()));

        UUID result = service.getOrCreateAnonymousId(REAL_IDENTITY);

        assertThat(result).isEqualTo(existingId);
        verify(repository, never()).save(any());
    }

    @Test
    void getOrCreateAnonymousId_newIdentity_savesMappingAndReturnsGeneratedId() {
        UUID generatedId = UUID.randomUUID();
        when(repository.findByIdentityHash(anyString())).thenReturn(Optional.empty());
        when(repository.save(any(IdentityMapping.class))).thenAnswer(inv -> {
            IdentityMapping m = inv.getArgument(0);
            m.setAnonymousId(generatedId);
            return m;
        });

        UUID result = service.getOrCreateAnonymousId(REAL_IDENTITY);

        assertThat(result).isEqualTo(generatedId);
        ArgumentCaptor<IdentityMapping> captor = ArgumentCaptor.forClass(IdentityMapping.class);
        verify(repository).save(captor.capture());
        IdentityMapping saved = captor.getValue();
        assertThat(saved.getRealIdentity()).isEqualTo(REAL_IDENTITY);
        // SHA-256 rendered as hex = 64 chars, and it must never be the plain identity.
        assertThat(saved.getIdentityHash()).hasSize(64).matches("[0-9a-f]+").doesNotContain(REAL_IDENTITY);
        assertThat(saved.getSalt()).isNotBlank();
    }

    @Test
    void getOrCreateAnonymousId_sameIdentity_alwaysLooksUpSameHash() {
        when(repository.findByIdentityHash(anyString()))
                .thenReturn(Optional.of(IdentityMapping.builder().anonymousId(UUID.randomUUID()).build()));

        service.getOrCreateAnonymousId(REAL_IDENTITY);
        service.getOrCreateAnonymousId(REAL_IDENTITY);

        ArgumentCaptor<String> hashes = ArgumentCaptor.forClass(String.class);
        verify(repository, times(2)).findByIdentityHash(hashes.capture());
        assertThat(hashes.getAllValues().get(0)).isEqualTo(hashes.getAllValues().get(1));
    }

    @Test
    void getOrCreateAnonymousId_differentHashSalt_producesDifferentHash() {
        when(repository.findByIdentityHash(anyString()))
                .thenReturn(Optional.of(IdentityMapping.builder().anonymousId(UUID.randomUUID()).build()));

        service.getOrCreateAnonymousId(REAL_IDENTITY);
        ReflectionTestUtils.setField(service, "hashSalt", "another-salt");
        service.getOrCreateAnonymousId(REAL_IDENTITY);

        ArgumentCaptor<String> hashes = ArgumentCaptor.forClass(String.class);
        verify(repository, times(2)).findByIdentityHash(hashes.capture());
        assertThat(hashes.getAllValues().get(0)).isNotEqualTo(hashes.getAllValues().get(1));
    }

    @Test
    void resolveRealIdentity_existingId_returnsRealIdentity() {
        UUID anonymousId = UUID.randomUUID();
        when(repository.findById(anonymousId)).thenReturn(Optional.of(
                IdentityMapping.builder().anonymousId(anonymousId).realIdentity(REAL_IDENTITY).build()));

        assertThat(service.resolveRealIdentity(anonymousId)).isEqualTo(REAL_IDENTITY);
    }

    @Test
    void resolveRealIdentity_unknownId_throwsNotFound() {
        UUID anonymousId = UUID.randomUUID();
        when(repository.findById(anonymousId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveRealIdentity(anonymousId))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND))
                .hasMessageContaining("Identity not found");
    }
}
