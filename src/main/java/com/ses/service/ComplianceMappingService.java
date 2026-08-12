package com.ses.service;

import com.ses.entity.ComplianceMappingVersion;

import java.util.List;

/**
 * G2 mapping version管理（Phase A step 3の第一increment）。
 *  - create: canonicalizerでmapping_hashを計算し、status=DRAFTで登録（client supplied hashは信頼しない）
 *  - transition: DRAFT→PROVISIONAL_REVIEWED（freeze。source completeness・96 stable ID・review policyを検証）
 *  - PROVISIONAL_REVIEWED→ACTIVEは実actor承認event・資格保有者Review等の証跡gate（後続incrementで実装）
 */
public interface ComplianceMappingService {

    ComplianceMappingVersion create(String mappingCode, String mappingVersion,
                                    java.time.LocalDate effectiveFrom, java.time.LocalDate effectiveTo,
                                    java.util.List<com.ses.dto.compliance.ComplianceMappingSourceInput> sources);

    ComplianceMappingVersion transition(Long mappingId, String toStatus);

    ComplianceMappingVersion transition(Long mappingId, String toStatus, Long approvalEventId);

    ComplianceMappingVersion promoteFutureToActive(Long mappingId);

    List<ComplianceMappingVersion> list();

    ComplianceMappingVersion getById(Long mappingId);
}
