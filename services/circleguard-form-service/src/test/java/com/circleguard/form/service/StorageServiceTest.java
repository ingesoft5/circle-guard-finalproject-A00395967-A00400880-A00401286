package com.circleguard.form.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StorageServiceTest {

    @TempDir
    Path uploadDir;

    private StorageService storageService;

    @BeforeEach
    void setUp() {
        storageService = new StorageService();
        // The upload root is hard-coded; redirect it so tests write into an isolated temp dir.
        ReflectionTestUtils.setField(storageService, "root", uploadDir);
    }

    @Test
    void store_writesFileContentToUploadDirectory() throws IOException {
        MockMultipartFile file = new MockMultipartFile(
                "file", "certificate.pdf", "application/pdf", "medical data".getBytes(StandardCharsets.UTF_8));

        String filename = storageService.store(file);

        Path stored = uploadDir.resolve(filename);
        assertThat(stored).exists();
        assertThat(Files.readString(stored)).isEqualTo("medical data");
    }

    @Test
    void store_prefixesOriginalNameWithUuid() {
        MockMultipartFile file = new MockMultipartFile("file", "certificate.pdf", "application/pdf", new byte[]{1});

        String filename = storageService.store(file);

        assertThat(filename).matches("[0-9a-f-]{36}_certificate\\.pdf");
    }

    @Test
    void store_sameOriginalNameTwice_producesTwoDistinctFiles() {
        MockMultipartFile file = new MockMultipartFile("file", "certificate.pdf", "application/pdf", new byte[]{1});

        String first = storageService.store(file);
        String second = storageService.store(file);

        assertThat(first).isNotEqualTo(second);
        assertThat(uploadDir.resolve(first)).exists();
        assertThat(uploadDir.resolve(second)).exists();
    }

    @Test
    void store_whenReadingUploadFails_wrapsErrorInRuntimeException() throws IOException {
        MultipartFile broken = mock(MultipartFile.class);
        when(broken.getOriginalFilename()).thenReturn("broken.pdf");
        when(broken.getInputStream()).thenThrow(new IOException("stream closed"));

        assertThatThrownBy(() -> storageService.store(broken))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Could not store the file. Error: stream closed");
        try (var files = Files.list(uploadDir)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void constructor_createsDefaultUploadDirectory() {
        Path defaultRoot = (Path) ReflectionTestUtils.getField(new StorageService(), "root");

        assertThat(defaultRoot).isDirectory();
    }
}
