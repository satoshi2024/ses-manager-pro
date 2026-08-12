package com.ses.service.compliance;

import com.ses.entity.ComplianceMappingSource;
import com.ses.entity.ComplianceMappingVersion;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G2 canonicalizer（Phase A step 3）のL1〜L2検証。
 *  - 96 stable row manifestの読込（field-mapping.md §3.5のmirror）
 *  - mapping_hashの決定的計算（同じ入力→同じhash・並び順非依存）
 *  - §6.2の除外（status/actor/UI表示順）を含めない
 */
class ComplianceMappingCanonicalizerTest {

    private final ComplianceMappingCanonicalizer canonicalizer = new ComplianceMappingCanonicalizer();

    @Test
    void manifestは96行を読み込む() throws Exception {
        String csv = new String(new ClassPathResource("compliance/mapping-manifest.csv")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        long rows = csv.lines().filter(l -> !l.isBlank() && !l.startsWith("row_id,")).count();
        assertEquals(96, rows, "manifestは96 stable row（FM-C-01〜FM-L-30）");
        assertEquals(96, ComplianceMappingCanonicalizer.MANIFEST.size());
        assertTrue(ComplianceMappingCanonicalizer.MANIFEST.stream().anyMatch(r -> r.rowId().equals("FM-C-01")));
        assertTrue(ComplianceMappingCanonicalizer.MANIFEST.stream().anyMatch(r -> r.rowId().equals("FM-L-30")));
    }

    @Test
    void mappingHashは決定的でsource並び順に依存しない() {
        ComplianceMappingVersion v = new ComplianceMappingVersion();
        v.setMappingCode("G2-MAPPING");
        v.setMappingVersion("MAPPING-2026-07");
        v.setEffectiveFrom(LocalDate.of(2026, 7, 1));
        v.setEffectiveTo(LocalDate.of(2026, 9, 30));

        ComplianceMappingSource s1 = source("SRC-C", "https://example/src-c", "2026-07", LocalDate.of(2026, 8, 9));
        ComplianceMappingSource s2 = source("SRC-L", "https://example/src-l", "2026-07", LocalDate.of(2026, 8, 9));
        ComplianceMappingSource s3 = source("SRC-E", "https://example/src-e", "2026-07", LocalDate.of(2026, 8, 9));
        ComplianceMappingSource s4 = source("SRC-N", "https://example/src-n", "2026-07", LocalDate.of(2026, 8, 9));
        ComplianceMappingSource s5 = source("SRC-INDEX", "https://example/index", "2026-07", LocalDate.of(2026, 8, 9));

        String hash1 = canonicalizer.computeMappingHash(v, List.of(s1, s2, s3, s4, s5));
        String hash2 = canonicalizer.computeMappingHash(v, List.of(s5, s3, s1, s4, s2));
        assertEquals(hash1, hash2, "source並び順に依存しない");
        assertEquals(64, hash1.length(), "SHA-256・64 lowercase hex");

        // 内容変更でhashが変わる
        ComplianceMappingSource s1modified = source("SRC-C", "https://example/src-c-v2", "2026-07", LocalDate.of(2026, 8, 9));
        assertNotEquals(hash1, canonicalizer.computeMappingHash(v, List.of(s1modified, s2, s3, s4, s5)));

        // status/actor/UI表示順は含めない（statusを変えてもhash不変）
        ComplianceMappingVersion vStatus = new ComplianceMappingVersion();
        vStatus.setMappingCode("G2-MAPPING");
        vStatus.setMappingVersion("MAPPING-2026-07");
        vStatus.setEffectiveFrom(LocalDate.of(2026, 7, 1));
        vStatus.setEffectiveTo(LocalDate.of(2026, 9, 30));
        vStatus.setStatus("ACTIVE");
        vStatus.setActiveSlot(1);
        assertEquals(hash1, canonicalizer.computeMappingHash(vStatus, List.of(s1, s2, s3, s4, s5)),
                "status/active_slot等はcanonical payloadに含めない");
    }

    private ComplianceMappingSource source(String code, String url, String version, LocalDate confirmedOn) {
        ComplianceMappingSource source = new ComplianceMappingSource();
        source.setSourceCode(code);
        source.setSourceUrl(url);
        source.setSourceVersion(version);
        source.setConfirmedOn(confirmedOn);
        source.setEffectiveFrom(LocalDate.of(2026, 7, 1));
        source.setEffectiveTo(LocalDate.of(2026, 9, 30));
        return source;
    }
}
