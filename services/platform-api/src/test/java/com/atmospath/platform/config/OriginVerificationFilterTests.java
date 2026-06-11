package com.atmospath.platform.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "atmospath.api-origin-verify-secret=test-secret")
@AutoConfigureMockMvc
class OriginVerificationFilterTests {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void rejectsRequestsWithoutCloudFrontOriginSecret() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isForbidden());
    }

    @Test
    void acceptsRequestsWithCloudFrontOriginSecret() throws Exception {
        mockMvc.perform(get("/health").header("X-Origin-Verify", "test-secret"))
                .andExpect(status().isOk());
    }
}
