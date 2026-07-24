package com.ses.service.skillsheet.impl;

import com.ses.dto.bpavailability.ParsedBpAvailabilityDto;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MockBpAvailabilityParseServiceImplTest {

    private final MockBpAvailabilityParseServiceImpl parser = new MockBpAvailabilityParseServiceImpl();

    @Test
    void parse_extractsSkills() {
        String text = "JavaとSpring Bootの経験があります。";
        ParsedBpAvailabilityDto dto = parser.parse(text);

        assertNotNull(dto);
        assertNotNull(dto.getAvailability());
        assertEquals("M.M", dto.getAvailability().getInitialName());
        assertTrue(dto.getAvailability().getSkills().contains("Java"));
        assertTrue(dto.getAvailability().getSkills().contains("Spring Boot"));
    }
}
