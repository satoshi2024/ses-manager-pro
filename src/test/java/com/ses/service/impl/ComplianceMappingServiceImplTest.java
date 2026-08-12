package com.ses.service.impl;

import com.ses.dto.compliance.ComplianceMappingSourceInput;
import com.ses.entity.ComplianceMappingVersion;
import com.ses.mapper.ComplianceMappingVersionMapper;
import com.ses.service.ComplianceMappingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G2 mapping version service（Phase A step 3）のL2〜L3検証。
 *  - create: canonicalizerでmapping_hash計算・DRAFT登録
 *  - DRAFT→PROVISIONAL_REVIEWED: source completeness必須・freeze
 *  - ACTIVE化は証跡gateで保留
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Sql(scripts = "/sql/engineer-schema-h2.sql")
class ComplianceMappingServiceImplTest {

    @Autowired
    private ComplianceMappingService complianceMappingService;

    @Autowired
    private ComplianceMappingVersionMapper versionMapper;

    @Test
    void createはcanonicalizerでhashを計算しDRAFTで登録する() {
        ComplianceMappingVersion version = complianceMappingService.create(
                "G2-MAPPING", "MAPPING-2026-07",
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30),
                allSources());

        assertNotNull(version.getId());
        assertEquals("DRAFT", version.getStatus());
        assertEquals(64, version.getMappingHash().length());
        assertEquals(version.getMappingHash(), versionMapper.selectById(version.getId()).getMappingHash());
    }

    @Test
    void DRAFTからPROVISIONAL_REVIEWEDへはsourceCompletenessを検証して遷移する() {
        // source不足（SRC-INDEX欠落）→ 400
        List<ComplianceMappingSourceInput> incomplete = new java.util.ArrayList<>(allSources());
        incomplete.removeIf(s -> "SRC-INDEX".equals(s.getSourceCode()));
        ComplianceMappingVersion incompleteVersion = complianceMappingService.create(
                "G2-MAPPING", "MAPPING-2026-07-TEST-1",
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30), incomplete);
        assertThrows(com.ses.common.exception.BusinessException.class,
                () -> complianceMappingService.transition(incompleteVersion.getId(), "PROVISIONAL_REVIEWED"));

        // source完全 → 遷移成功・freeze（status変更でhash不変）
        ComplianceMappingVersion complete = complianceMappingService.create(
                "G2-MAPPING", "MAPPING-2026-07-TEST-2",
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30), allSources());
        String draftHash = complete.getMappingHash();
        ComplianceMappingVersion reviewed = complianceMappingService.transition(complete.getId(), "PROVISIONAL_REVIEWED");
        assertEquals("PROVISIONAL_REVIEWED", reviewed.getStatus());
        assertEquals(draftHash, reviewed.getMappingHash(), "freeze: hash不変");

        // PROVISIONAL_REVIEWED→PROVISIONAL_REVIEWEDは不正遷移
        assertThrows(com.ses.common.exception.BusinessException.class,
                () -> complianceMappingService.transition(reviewed.getId(), "PROVISIONAL_REVIEWED"));
    }

    @Test
    void ACTIVE化は証跡gateで保留される() {
        ComplianceMappingVersion version = complianceMappingService.create(
                "G2-MAPPING", "MAPPING-2026-07-TEST-3",
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30), allSources());
        complianceMappingService.transition(version.getId(), "PROVISIONAL_REVIEWED");
        com.ses.common.exception.BusinessException e = assertThrows(
                com.ses.common.exception.BusinessException.class,
                () -> complianceMappingService.transition(version.getId(), "ACTIVE"));
        assertTrue(e.getMessageKey() == null || e.getMessageKey().contains("activeGated"),
                "ACTIVE化は証跡gateで保留");
    }

    private List<ComplianceMappingSourceInput> allSources() {
        return List.of(
                source("SRC-C", "https://jsite.mhlw.go.jp/hokkaido-roudoukyoku/content/contents/002722622.pdf", "2026-07"),
                source("SRC-E", "https://jsite.mhlw.go.jp/hokkaido-roudoukyoku/content/contents/002722631.pdf", "2026-07"),
                source("SRC-N", "https://jsite.mhlw.go.jp/hokkaido-roudoukyoku/content/contents/002722633.pdf", "2026-07"),
                source("SRC-L", "https://jsite.mhlw.go.jp/hokkaido-roudoukyoku/content/contents/002722641.pdf", "2026-07"),
                source("SRC-INDEX", "https://jsite.mhlw.go.jp/hokkaido-roudoukyoku/hourei_seido_tetsuzuki/roudousha_haken/newpage_00448.html", "2026-07"));
    }

    private ComplianceMappingSourceInput source(String code, String url, String version) {
        ComplianceMappingSourceInput input = new ComplianceMappingSourceInput();
        input.setSourceCode(code);
        input.setSourceUrl(url);
        input.setSourceVersion(version);
        input.setConfirmedOn(LocalDate.of(2026, 8, 9));
        input.setEffectiveFrom(LocalDate.of(2026, 7, 1));
        input.setEffectiveTo(LocalDate.of(2026, 9, 30));
        return input;
    }
}
