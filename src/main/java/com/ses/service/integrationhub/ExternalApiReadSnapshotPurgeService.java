package com.ses.service.integrationhub;

import com.ses.mapper.ExternalApiReadSnapshotMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** A1 read snapshotを公開requestから分離して有限batchでpurgeするservice。 */
@Service
@RequiredArgsConstructor
public class ExternalApiReadSnapshotPurgeService {
    public static final int MAX_BATCH_SIZE = 32;

    private final ExternalApiReadSnapshotMapper mapper;

    /**
     * expiry indexで最大32 headerだけを選び、FK cascadeによりitemsと一緒に短いtransactionで削除する。
     * 例外時はtransaction全体をrollbackし、次回scheduler実行で同じexpired集合を再評価する。
     */
    @Transactional(rollbackFor = Exception.class)
    public int purgeExpiredBatch(Instant now, int requestedBatchSize) {
        if (now == null || requestedBatchSize < 1) {
            throw new IllegalArgumentException("invalid read snapshot purge request");
        }
        int limit = Math.min(requestedBatchSize, MAX_BATCH_SIZE);
        List<String> snapshotIds = mapper.selectExpiredSnapshotIds(now, limit);
        if (snapshotIds == null || snapshotIds.isEmpty()) {
            return 0;
        }
        if (snapshotIds.size() > limit || snapshotIds.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new IllegalStateException("read snapshot purge batch is invalid");
        }
        int deleted = mapper.deleteSnapshotsById(snapshotIds);
        if (deleted != snapshotIds.size()) {
            throw new IllegalStateException("read snapshot purge batch was partially deleted");
        }
        return deleted;
    }
}
