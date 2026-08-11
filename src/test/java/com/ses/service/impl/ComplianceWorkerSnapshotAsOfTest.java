package com.ses.service.impl;

import com.ses.entity.ContractComplianceWorkerSnapshot;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T066 M: 交付時点のworker snapshotを選択する契約を固定する。
 * 未来版・asOf不明版を過去帳票へ混入させない。
 */
class ComplianceWorkerSnapshotAsOfTest {

    @Test
    void 交付時点以前で最も新しいsnapshotだけを候補にする() {
        LocalDateTime asOf = LocalDateTime.of(2026, 8, 10, 12, 0);
        ContractComplianceWorkerSnapshot before = snapshot(1, asOf.minusDays(2));
        ContractComplianceWorkerSnapshot at = snapshot(2, asOf);
        ContractComplianceWorkerSnapshot future = snapshot(3, asOf.plusSeconds(1));
        ContractComplianceWorkerSnapshot unknown = snapshot(4, null);

        ContractComplianceWorkerSnapshot selected = ComplianceDocumentServiceImpl.selectWorkerSnapshotAsOf(
                List.of(before, at, future, unknown), asOf);

        assertThat(selected).isSameAs(at);
    }

    @Test
    void asOf不明ならworker固有項目を帳票へ渡さない() {
        ContractComplianceWorkerSnapshot snapshot = snapshot(1, LocalDateTime.now());

        assertThat(ComplianceDocumentServiceImpl.selectWorkerSnapshotAsOf(
                List.of(snapshot), null)).isNull();
    }

    private ContractComplianceWorkerSnapshot snapshot(int version, LocalDateTime snapshotAt) {
        ContractComplianceWorkerSnapshot snapshot = new ContractComplianceWorkerSnapshot();
        snapshot.setSnapshotVersion(version);
        snapshot.setSnapshotAt(snapshotAt);
        return snapshot;
    }
}
