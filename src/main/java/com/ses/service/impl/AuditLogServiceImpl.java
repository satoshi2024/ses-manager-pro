package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.backup.quiesce.BackupQuiesceMetrics;
import com.ses.backup.quiesce.BackupQuiesceProperties;
import com.ses.backup.quiesce.BackupWriteGate;
import com.ses.backup.quiesce.BackupWriteQuiescedException;
import com.ses.common.audit.ActorType;
import com.ses.common.audit.ActorAttribution;
import com.ses.common.audit.ConfirmationSource;
import com.ses.entity.AuditLog;
import com.ses.common.util.LogRedaction;
import com.ses.mapper.AuditLogMapper;
import com.ses.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogServiceImpl implements AuditLogService {

    private final AuditLogMapper auditLogMapper;

    /**
     * 解除後の監査（{@link #recordCaptured}）の時刻の検査の余裕。静止の最長の保持時間に足す: 解除の後に一覧を executor へ
     * 投入するまでの待ち・DB の失敗の 3 回の繰返し（接続の取得の待ちは最長 30 秒）・時計の補正に 5 分、書いている途中で
     * 次の checkpoint の静止が始まり残りが次の解除へ回る場合（D3-P3-5）にその checkpoint の最大保持時間。
     */
    static final Duration CAPTURED_MARGIN_BASE = Duration.ofMinutes(5);
    /**
     * 解除後の監査の時刻が今より「未来」に見えても許す幅（2b review P3-7）。logout の時点から書くまでの間に NTP が時計を少し戻すと、
     * 取っておいた時刻が今より後になり監査を失う。NTP の通常の補正（数十 ms〜1 秒程度）を十分に覆い、利用者の操作の順を
     * 監査の上で入れ替えるほどには大きくない 5 秒にする。これを超える未来の時刻は従来どおり書かずに WARN を残す。
     */
    static final Duration CAPTURED_FUTURE_TOLERANCE = Duration.ofSeconds(5);

    private BackupWriteGate backupWriteGate;
    private BackupQuiesceProperties backupQuiesceProperties = new BackupQuiesceProperties();
    private BackupQuiesceMetrics backupQuiesceMetrics;

    /** backup の書込み静止の gate（RB-QUIESCE U-9）。無い context（slice の試験等）では常に書く。 */
    @Autowired(required = false)
    public void setBackupWriteGate(BackupWriteGate backupWriteGate) {
        this.backupWriteGate = backupWriteGate;
    }

    @Autowired(required = false)
    public void setBackupQuiesceProperties(BackupQuiesceProperties backupQuiesceProperties) {
        if (backupQuiesceProperties != null) {
            this.backupQuiesceProperties = backupQuiesceProperties;
        }
    }

    @Autowired(required = false)
    public void setBackupQuiesceMetrics(BackupQuiesceMetrics backupQuiesceMetrics) {
        this.backupQuiesceMetrics = backupQuiesceMetrics;
    }

    @Override
    public void record(String username, String method, String uri, int status) {
        record(username, method, uri, status, "ses-manager", status >= 200 && status < 400);
    }

    @Override
    public void record(String username, String method, String uri, int status, String applicationCode, boolean successFlag) {
        if (writesBlocked()) {
            // backup の書込み静止の間（permit の無い thread）は DB へ書かず、同じ内容を WARN に残す（U-9。design.md §6.4）
            log.warn("errorCode=BACKUP_QUIESCE_AUDIT_DEFERRED backup の静止中のため監査ログを DB へ書かず log に残しました: "
                            + "user={} method={} uri={} status={} applicationCode={} success={}",
                    username == null ? "-" : username, method, uri, status, applicationCode, successFlag);
            if (backupQuiesceMetrics != null) {
                backupQuiesceMetrics.skippedWrite("best_effort_audit");
            }
            return;
        }
        try {
            AuditLog entry = newEntry(username, method, uri, status, applicationCode, successFlag);
            AuditLogService.CapturedContext.capture().applyTo(entry);
            auditLogMapper.insert(entry);
        } catch (Exception e) {
            // 監査ログの永続化失敗は本来のAPI処理に影響させない
            log.warn("監査ログの記録に失敗: method={} status={} exceptionClass={} detail={}",
                    method, status, LogRedaction.exceptionType(e), LogRedaction.safeThrowableSummary(e));
        }
    }

    @Override
    public void recordRequired(String username, String method, String uri, int status,
                               String applicationCode, boolean successFlag) {
        if (writesBlocked()) {
            // 必須の監査は書けなければ操作も止める。静止中は 503 にする（design.md §6.4）
            throw new BackupWriteQuiescedException("required-audit");
        }
        AuditLog entry = newEntry(username, method, uri, status, applicationCode, successFlag);
        AuditLogService.CapturedContext.capture().applyTo(entry);
        if (auditLogMapper.insert(entry) != 1) {
            throw new IllegalStateException("重要security監査を永続化できません");
        }
    }

    /**
     * 解除後の書込みの一覧が、静止中の logout の監査を logout の時点の値で書く（U-8。design.md §6.13。D4-P3-3）。
     *
     * <p>取っておいた時刻は {@link #record} と同じ時計（JVM の時刻帯の {@link LocalDateTime#now()}）で
     * {@code [今 − 静止の最長の保持時間 − 余裕, 今 + 5 秒]} に収まる場合だけ書く（未来の 5 秒は時計の補正の幅。
     * {@link #CAPTURED_FUTURE_TOLERANCE}）。範囲の外（古すぎる・未来）は書かずに内容つきの WARN を
     * 残す（過去の時刻の監査の行を任意に作れないようにする）。余裕は {@link #CAPTURED_MARGIN_BASE} と checkpoint の最大保持時間。
     * DB の失敗は例外で返す（呼出し元が繰り返す）。
     */
    @Override
    public void recordCaptured(String username, String method, String uri, int status, String applicationCode,
                               boolean successFlag, AuditLogService.CapturedContext context) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime createdAt = context == null ? null : context.createdAt();
        LocalDateTime oldest = now.minus(backupQuiesceProperties.longestHold())
                .minus(CAPTURED_MARGIN_BASE).minus(backupQuiesceProperties.getMaxHoldCheckpoint());
        LocalDateTime newest = now.plus(CAPTURED_FUTURE_TOLERANCE);
        if (createdAt == null || createdAt.isAfter(newest) || createdAt.isBefore(oldest)) {
            log.warn("errorCode=BACKUP_QUIESCE_DEFERRED_WRITE_DROPPED 解除後の監査の時刻が許す範囲（{} 〜 {}）の外のため書きません: "
                            + "user={} method={} uri={} status={} applicationCode={} createdAt={}",
                    oldest, newest, username == null ? "-" : username, method, uri, status, applicationCode, createdAt);
            return;
        }
        AuditLog entry = newEntry(username, method, uri, status, applicationCode, successFlag);
        context.applyTo(entry);
        if (auditLogMapper.insert(entry) != 1) {
            throw new IllegalStateException("解除後の監査を永続化できません");
        }
    }

    private static AuditLog newEntry(String username, String method, String uri, int status, String applicationCode,
                                     boolean successFlag) {
        AuditLog entry = new AuditLog();
        entry.setUsername(username);
        entry.setMethod(method);
        entry.setUri(uri);
        entry.setStatus(status);
        entry.setApplicationCode(applicationCode);
        entry.setSuccessFlag(successFlag);
        return entry;
    }

    private boolean writesBlocked() {
        BackupWriteGate gate = backupWriteGate;
        return gate != null && gate.writesBlockedForCurrentThread();
    }

    @Override
    public void recordDomainEventRequired(AuditLog entry) {
        if (entry == null || entry.getActorType() == null || entry.getConfirmationSource() == null) {
            throw new IllegalArgumentException("ドメイン監査には主体とチャネルが必要です");
        }
        try {
            new ActorAttribution(
                    ActorType.valueOf(entry.getActorType()),
                    ConfirmationSource.valueOf(entry.getConfirmationSource()),
                    entry.getHumanUserId(), entry.getCorrelationId(), entry.getIdempotencyKey());
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("ドメイン監査の主体/チャネルが不正です", ex);
        }
        entry.setCreatedAt(entry.getCreatedAt() != null ? entry.getCreatedAt() : LocalDateTime.now());
        if (auditLogMapper.insert(entry) != 1) {
            throw new IllegalStateException("ドメイン監査を永続化できません");
        }
    }

    /**
     * 呼出側transactionの成否から独立して必須記録するドメイン監査（NF08 AI外部送信のATTEMPT/RESULT/DENIED）。
     * <p>{@code REQUIRES_NEW} の別transactionでcommitするため、外側transactionが後でrollbackしても記録は残る。
     * 外部送信のように「送信した事実」が外側の業務処理の成否と無関係に残らなければならない監査に使う。
     * 検証・永続化の内容は {@link #recordDomainEventRequired} と同じで、既存methodの意味（呼出側transactionへの参加。
     * D1 bootstrap等の必須監査が依存）は変えない。</p>
     * <p>独立transactionはSpring proxy経由の呼出しでだけ成立する。proxyを通らない呼出し（自己呼出し・手動構築）で
     * 外側transactionに参加してしまうと独立性を保証できないため、このmethodで開始されたtransaction内でなければ
     * 記録せずに失敗させる（fail-closed）。別connectionを取得できない場合（pool枯渇等）もここで失敗する。</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void recordDomainEventRequiredInNewTransaction(AuditLog entry) {
        String transactionName = TransactionSynchronizationManager.getCurrentTransactionName();
        if (!TransactionSynchronizationManager.isActualTransactionActive() || transactionName == null
                || !transactionName.endsWith(".recordDomainEventRequiredInNewTransaction")) {
            throw new IllegalStateException("独立transactionでドメイン監査を記録できません");
        }
        recordDomainEventRequired(entry);
    }

    @Override
    public Page<AuditLog> page(long current, long size, String username, String method) {
        Page<AuditLog> page = new Page<>(current, size);
        LambdaQueryWrapper<AuditLog> qw = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(username)) {
            qw.like(AuditLog::getUsername, username);
        }
        if (StringUtils.hasText(method)) {
            qw.eq(AuditLog::getMethod, method);
        }
        qw.orderByDesc(AuditLog::getCreatedAt);
        return auditLogMapper.selectPage(page, qw);
    }
}
