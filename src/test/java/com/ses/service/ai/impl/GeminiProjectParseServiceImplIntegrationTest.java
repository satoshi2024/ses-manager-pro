package com.ses.service.ai.impl;

import com.ses.service.ai.ProjectParseService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "ai.provider=gemini")
public class GeminiProjectParseServiceImplIntegrationTest {

    @Autowired
    private ProjectParseService projectParseService;

    @Test
    void providerIsGemini_loadsGeminiImplementation() {
        assertTrue(projectParseService instanceof GeminiProjectParseServiceImpl, 
            "ai.provider=gemini should load GeminiProjectParseServiceImpl");
    }
}
