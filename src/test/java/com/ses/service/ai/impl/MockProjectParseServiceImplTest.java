package com.ses.service.ai.impl;

import com.ses.dto.projectingestion.ParsedProjectDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

public class MockProjectParseServiceImplTest {

    private final MockProjectParseServiceImpl service = new MockProjectParseServiceImpl();

    @Test
    void parse_extractsNameAndPriceAndSkills() {
        String text = """
                案件名：テストプロジェクト
                単価：70万円
                勤務地：東京
                必須スキル：Java, Spring Boot, MySQL
                """;
        
        ParsedProjectDto result = service.parse(text);
        
        assertNotNull(result);
        assertNotNull(result.getProject());
        assertEquals("テストプロジェクト", result.getProject().getName());
        assertEquals(new BigDecimal("700000"), result.getProject().getMinUnitPrice());
        assertEquals(new BigDecimal("700000"), result.getProject().getMaxUnitPrice());
        assertEquals("東京", result.getProject().getLocation());
        
        assertNotNull(result.getSkills());
        assertEquals(3, result.getSkills().size());
        assertTrue(result.getSkills().stream().anyMatch(s -> s.getName().equals("Java")));
        assertTrue(result.getSkills().stream().anyMatch(s -> s.getName().equals("Spring Boot")));
        assertTrue(result.getSkills().stream().anyMatch(s -> s.getName().equals("MySQL")));
    }
}
