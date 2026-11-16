package com.example.saas;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

class ErrorsTest {
    @Test
    void databaseTerminationThatAlsoBreaksRollbackIs503WithoutInternalDetails() throws Exception {
        var mvc =
                MockMvcBuilders.standaloneSetup(new FailingDatabase())
                        .setControllerAdvice(new Errors())
                        .build();
        mvc.perform(get("/failed-rollback"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail").value("Retry later"));
    }

    @RestController
    static class FailingDatabase {
        @GetMapping("/failed-rollback")
        Object rollback() {
            throw new org.springframework.transaction.TransactionSystemException(
                    "JDBC rollback failed", new java.sql.SQLException("Connection is closed"));
        }
    }
}
