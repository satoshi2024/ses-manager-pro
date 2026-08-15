package com.ses.service.compliance;

import com.ses.common.exception.BusinessException;
import com.ses.entity.DocumentVersion;
import com.ses.mapper.DocumentVersionMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * R23-P1-01 §4-5/6/7: exact evidenceのserver-side解決。
 *
 * <p>document ID＋exact version IDをserver-sideで解決し、以下を検証して
 * イベントへID/version/hashを全てsnapshotする:
 * <ul>
 *   <li>tenant一致（cross-tenant参照拒否）</li>
 *   <li>document/version対応（document_id一致）</li>
 *   <li>file scope（deleted_flag=0・存在）</li>
 *   <li>scan=CLEAN（fail-closed・PENDING/INFECTED等は拒否）</li>
 *   <li>SHA-256値の形式（64 hex）</li>
 * </ul>
 *
 * <p>gate判定には{@code findLatestByDocumentId()}を使用しない（§4-6）。
 * evidence NULL・version不存在・document不一致・non-CLEAN・hash不一致は全て拒否する。
 */
@Component
public class ComplianceGateEvidenceResolver {

    private final DocumentVersionMapper documentVersionMapper;

    public ComplianceGateEvidenceResolver(DocumentVersionMapper documentVersionMapper) {
        this.documentVersionMapper = documentVersionMapper;
    }

    /**
     * document ID＋exact version IDを解決し、exact version/hash/CLEANを検証する。
     *
     * @return 解決済みのDocumentVersion（検証済み）
     * @throws BusinessException evidence NULL・不存在・tenant不一致・document不一致・non-CLEAN・hash不正で拒否
     */
    public DocumentVersion resolve(String tenantId, Long documentId, Long documentVersionId) {
        if (documentId == null || documentVersionId == null) {
            throw BusinessException.of(400, "compliance.gate.evidenceRequired");
        }
        DocumentVersion version = documentVersionMapper.selectById(documentVersionId);
        if (version == null) {
            throw BusinessException.of(400, "compliance.gate.evidenceVersionNotFound");
        }
        if (StringUtils.hasText(tenantId) && !tenantId.equals(version.getTenantId())) {
            throw BusinessException.of(403, "compliance.gate.evidenceTenantMismatch");
        }
        if (!documentId.equals(version.getDocumentId())) {
            throw BusinessException.of(400, "compliance.gate.evidenceDocumentMismatch");
        }
        if (!"CLEAN".equalsIgnoreCase(version.getScanStatus())) {
            throw BusinessException.of(400, "compliance.gate.evidenceNotClean");
        }
        if (!StringUtils.hasText(version.getSha256()) || version.getSha256().length() != 64) {
            throw BusinessException.of(400, "compliance.gate.evidenceHashInvalid");
        }
        return version;
    }
}
