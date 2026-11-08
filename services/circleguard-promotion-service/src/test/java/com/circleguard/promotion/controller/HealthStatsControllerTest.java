package com.circleguard.promotion.controller;

import com.circleguard.promotion.security.SecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HealthStatsController.class)
@Import(SecurityConfig.class)
class HealthStatsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private Neo4jClient neo4jClient;

    @Mock
    private Neo4jClient.UnboundRunnableSpec querySpec;
    @Mock
    private Neo4jClient.OngoingBindSpec<Object, Neo4jClient.RunnableSpec> bindSpec;
    @Mock
    private Neo4jClient.RecordFetchSpec<Map<String, Object>> fetchSpec;

    private AutoCloseable mocks;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        when(neo4jClient.query(anyString())).thenReturn(querySpec);
        when(querySpec.bind(any())).thenReturn((Neo4jClient.OngoingBindSpec) bindSpec);
        when(bindSpec.to(anyString())).thenReturn(querySpec);
        when(querySpec.fetch()).thenReturn(fetchSpec);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() throws Exception {
        mocks.close();
    }

    private static Map<String, Object> row(String status, long total) {
        Map<String, Object> row = new HashMap<>();
        row.put("status", status);
        row.put("total", total);
        return row;
    }

    @Test
    void getStats_aggregatesCountsPerStatusAndGrandTotal() throws Exception {
        when(fetchSpec.all()).thenReturn(List.of(row("ACTIVE", 10), row("SUSPECT", 3), row(null, 2)));

        mockMvc.perform(get("/api/v1/health-status/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeCount").value(10))
                .andExpect(jsonPath("$.suspectCount").value(3))
                .andExpect(jsonPath("$.unknownCount").value(2))
                .andExpect(jsonPath("$.totalUsers").value(15))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void getStats_noUsers_returnsZeroTotal() throws Exception {
        when(fetchSpec.all()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/health-status/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalUsers").value(0));
    }

    @Test
    void getStatsByDepartment_bindsDepartmentAndEchoesIt() throws Exception {
        when(fetchSpec.all()).thenReturn(List.of(row("CONFIRMED", 1), row("ACTIVE", 4)));

        mockMvc.perform(get("/api/v1/health-status/stats/department/{department}", "Engineering"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.department").value("Engineering"))
                .andExpect(jsonPath("$.confirmedCount").value(1))
                .andExpect(jsonPath("$.activeCount").value(4))
                .andExpect(jsonPath("$.totalUsers").value(5));

        verify(querySpec).bind("Engineering");
        verify(bindSpec).to("dept");
    }
}
