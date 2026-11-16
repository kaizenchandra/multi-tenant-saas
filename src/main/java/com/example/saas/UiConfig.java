package com.example.saas;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
class UiConfig {
    @GetMapping("/ui-config")
    Map<String, String> config(@Value("${saas.issuer}") String issuer) {
        return Map.of("oidc", issuer + "/protocol/openid-connect", "clientId", "saas-ui");
    }
}
