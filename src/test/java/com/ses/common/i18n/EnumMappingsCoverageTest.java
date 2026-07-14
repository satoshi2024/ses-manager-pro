package com.ses.common.i18n;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

public class EnumMappingsCoverageTest {

    @Test
    public void testEnumMappingsCoverage() throws Exception {
        String sql = StreamUtils.copyToString(new ClassPathResource("db/migration/V1__create_tables.sql").getInputStream(), StandardCharsets.UTF_8);

        Pattern p = Pattern.compile("ENUM\\(([^)]+)\\)");
        Matcher m = p.matcher(sql);
        Set<String> sqlEnumValues = new HashSet<>();
        while (m.find()) {
            String[] vals = m.group(1).split(",");
            for (String val : vals) {
                String cleanVal = val.replaceAll("'", "").trim();
                if (cleanVal.matches(".*[\\u3040-\\u309F\\u30A0-\\u30FF\\u4E00-\\u9FAF].*")) {
                    sqlEnumValues.add(cleanVal);
                }
            }
        }

        Set<String> mappedValues = new HashSet<>();
        for (Map<String, String> map : EnumMappings.GROUPS.values()) {
            mappedValues.addAll(map.keySet());
        }
        
        Set<String> missing = new HashSet<>();
        for (String sqlVal : sqlEnumValues) {
            if (!mappedValues.contains(sqlVal)) {
                missing.add(sqlVal);
            }
        }
        assertTrue(missing.isEmpty(), "SQL ENUM values not mapped in EnumMappings: " + missing);
    }
}
