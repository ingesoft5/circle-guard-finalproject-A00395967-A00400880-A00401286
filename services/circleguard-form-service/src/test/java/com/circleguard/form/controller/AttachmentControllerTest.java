package com.circleguard.form.controller;

import com.circleguard.form.service.StorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Prueba del controlador aislado (@WebMvcTest): antes era @SpringBootTest y
// levantaba todo el contexto, incluido Flyway, que intentaba conectarse a un
// PostgreSQL real en localhost:5432 y fallaba en CI. El almacenamiento se
// simula; StorageService tiene sus propias pruebas.
@WebMvcTest(AttachmentController.class)
class AttachmentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private StorageService storageService;

    @Test
    void shouldUploadFile() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.pdf", "application/pdf", "test data".getBytes());
        when(storageService.store(any())).thenReturn("stored-test.pdf");

        mockMvc.perform(multipart("/api/v1/attachments").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filename").value("stored-test.pdf"));

        verify(storageService).store(argThat(f -> "test.pdf".equals(f.getOriginalFilename())));
    }

    @Test
    void uploadWithoutFilePart_returnsBadRequest() throws Exception {
        mockMvc.perform(multipart("/api/v1/attachments"))
                .andExpect(status().isBadRequest());

        verify(storageService, never()).store(any());
    }
}
