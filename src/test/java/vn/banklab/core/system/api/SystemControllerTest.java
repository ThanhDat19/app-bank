package vn.banklab.core.system.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.context.annotation.Import;
import vn.banklab.core.support.WebContractSecurity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SystemController.class)
@Import(WebContractSecurity.class)
class SystemControllerTest {

    @Autowired
    MockMvc mvc;

    @Test
    void exposesOnlySimulationMetadata() throws Exception {
        mvc.perform(get("/api/v1/system/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("core-banking-service"))
                .andExpect(jsonPath("$.mode").value("SIMULATION"))
                .andExpect(jsonPath("$.password").doesNotExist());
    }
}
