package com.ses.service.compliance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.dto.compliance.ComplianceFinding;
import com.ses.mapper.ComplianceFindingMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * rule評価結果を t_compliance_finding へupsert同期する（design §5.4）。
 *  - 検出中（evaluatedに存在）: 既存行はstatusを保持（ack済みがOPENへ戻らない）。
 *    ただしRESOLVEDは再検出でOPENへ戻る。EXCEPTION_APPROVEDは期限切れ判定をT065へ委ねる。
 *  - 非検出（evaluatedに不在）: OPEN/ACKNOWLEDGED/IN_PROGRESSはRESOLVEDへ（欠落補完で解消）。
 *    EXCEPTION_APPROVEDは保持。
 * rule実行はfindingのupsertのみで、契約や勤怠の業務状態を変更しない。
 */
@Component
public class ComplianceFindingStore {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_ACKNOWLEDGED = "ACKNOWLEDGED";
    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_RESOLVED = "RESOLVED";
    public static final String STATUS_EXCEPTION_APPROVED = "EXCEPTION_APPROVED";

    private final ComplianceFindingMapper findingMapper;

    public ComplianceFindingStore(ComplianceFindingMapper findingMapper) {
        this.findingMapper = findingMapper;
    }

    /** 同期結果の集計。 */
    public record SyncResult(int opened, int resolved, int kept) {
    }

    @Transactional
    public SyncResult sync(Long contractId, List<ComplianceFinding> evaluated) {
        List<com.ses.entity.ComplianceFinding> existing = findingMapper.selectList(
                new LambdaQueryWrapper<com.ses.entity.ComplianceFinding>()
                        .eq(com.ses.entity.ComplianceFinding::getContractId, contractId));
        Map<String, com.ses.entity.ComplianceFinding> byKey = new HashMap<>();
        for (com.ses.entity.ComplianceFinding entity : existing) {
            byKey.put(key(entity.getCode(), entity.getConditionFingerprint()), entity);
        }
        int opened = 0;
        int resolved = 0;
        int kept = 0;
        for (ComplianceFinding candidate : evaluated) {
            com.ses.entity.ComplianceFinding entity =
                    byKey.get(key(candidate.getCode(), candidate.getConditionFingerprint()));
            if (entity == null) {
                com.ses.entity.ComplianceFinding insert = new com.ses.entity.ComplianceFinding();
                insert.setTenantId("default");
                insert.setContractId(contractId);
                insert.setCode(candidate.getCode());
                insert.setSeverity(candidate.getSeverity());
                insert.setStatus(STATUS_OPEN);
                insert.setConditionFingerprint(candidate.getConditionFingerprint());
                insert.setDetectedAt(LocalDateTime.now());
                insert.setDueDate(candidate.getDueDate());
                findingMapper.insert(insert);
                opened++;
            } else {
                entity.setDueDate(candidate.getDueDate());
                if (STATUS_RESOLVED.equals(entity.getStatus())) {
                    entity.setStatus(STATUS_OPEN);
                    entity.setDetectedAt(LocalDateTime.now());
                    findingMapper.updateById(entity);
                    opened++;
                } else {
                    findingMapper.updateById(entity);
                    kept++;
                }
            }
        }
        for (com.ses.entity.ComplianceFinding entity : existing) {
            boolean stillDetected = evaluated.stream().anyMatch(c ->
                    key(c.getCode(), c.getConditionFingerprint())
                            .equals(key(entity.getCode(), entity.getConditionFingerprint())));
            if (stillDetected) {
                continue;
            }
            if (STATUS_OPEN.equals(entity.getStatus()) || STATUS_ACKNOWLEDGED.equals(entity.getStatus())
                    || STATUS_IN_PROGRESS.equals(entity.getStatus())) {
                entity.setStatus(STATUS_RESOLVED);
                entity.setResolutionNote("rule再実行で条件が解消したため自動解消");
                findingMapper.updateById(entity);
                resolved++;
            }
        }
        return new SyncResult(opened, resolved, kept);
    }

    private String key(String code, String fingerprint) {
        return code + "|" + (fingerprint == null ? "" : fingerprint);
    }
}
