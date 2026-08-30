package com.ses.service.integrationhub;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ses.entity.integrationhub.ApiRetentionHold;

import java.time.LocalDateTime;

/** NF-05 retention/legal hold/purge foundation。checkpointは削除可否の正本にしない。 */
public interface ApiRetentionPurgeService extends IService<ApiRetentionHold> {
    boolean acquireHold(String recordKind, Long recordId, String reasonCode, LocalDateTime now);

    boolean releaseHold(String recordKind, Long recordId, LocalDateTime now);

    PurgeReport purgeExpired(String recordKind, String retentionClass, LocalDateTime now, int maxRows);

    long advanceRestoreEpoch(String recordKind, String retentionClass, LocalDateTime now);

    record PurgeReport(int inspected, int purged, int held, long restoreEpoch) {
    }
}
