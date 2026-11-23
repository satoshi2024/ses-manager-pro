package com.ses.featureenablement.service;

import com.ses.common.audit.ActorType;
import com.ses.common.audit.ConfirmationSource;
import com.ses.config.MyBatisPlusConfig;
import com.ses.entity.AuditLog;
import com.ses.service.AuditLogService;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 利用者が管理者の role を得た時に、有効な管理者全員へ通知する（feature-enablement-console design §11.1 の review P3-11）。
 *
 * <p>管理者の role の付与は1名でできるため、盗まれた管理者が自分の別の OIDC 口座を管理者にして自分で承認する、という二人の承認の
 * 残る穴を、全ての管理者が気づけるようにする（承認の前に、直近に管理者の role を得た利用者が承認者でないことを確かめる運用。
 * RB §5.6）。通知は commit の後に best-effort で送り、送付の記録を同じ transaction の監査
 * {@code FEATURE_ADMIN_ROLE_GRANTED_NOTICE} に残す（利用者の ID だけ。氏名を書かない）。付与者と付与の時刻の記録（DDL）は
 * go-live の後の backlog（review P3-N10）。</p>
 */
@Component
public class AdministratorRoleGrantNotifier {

    private final FeatureEnablementNotifier notifier;
    private final AuditLogService auditLogService;
    private final Clock clock;

    public AdministratorRoleGrantNotifier(FeatureEnablementNotifier notifier, AuditLogService auditLogService,
                                          Clock clock) {
        this.notifier = notifier;
        this.auditLogService = auditLogService;
        this.clock = clock;
    }

    /**
     * 管理者の role を得た（作成・role の変更）。利用者の書込みの transaction の中で呼ぶ。
     *
     * @param grantedUserId    role を得た利用者
     * @param operatorUserId   操作した管理者（分からなければ null）
     * @param operatorUsername 操作した管理者の username（監査の {@code username} 列だけに使う）
     */
    public void administratorRoleGranted(Long grantedUserId, Long operatorUserId, String operatorUsername) {
        if (grantedUserId == null) {
            return;
        }
        LocalDateTime now = MyBatisPlusConfig.AutoFillMetaObjectHandler.rowTimestamp(clock);
        AuditLog entry = new AuditLog();
        boolean human = operatorUserId != null;
        entry.setUsername(human && operatorUsername != null ? operatorUsername : "SYSTEM");
        entry.setMethod(FeatureEnablementWriteSupport.AUDIT_METHOD);
        entry.setUri("/api/users");
        entry.setStatus(200);
        entry.setApplicationCode("FEATURE_ADMIN_ROLE_GRANTED_NOTICE");
        entry.setSuccessFlag(true);
        entry.setReferenceType("SYS_USER");
        entry.setReferenceId(grantedUserId);
        entry.setActorType(human ? ActorType.HUMAN.name() : ActorType.SYSTEM.name());
        entry.setConfirmationSource(human ? ConfirmationSource.MANUAL_API.name() : ConfirmationSource.SCHEDULER_POLL.name());
        entry.setHumanUserId(human ? operatorUserId : null);
        entry.setAfterState("{\"grantedUserId\":" + grantedUserId + ",\"role\":\"ADMIN\"}");
        entry.setCorrelationId(UUID.randomUUID().toString());
        entry.setCreatedAt(now);
        auditLogService.recordDomainEventRequired(entry);
        notifier.notifyAdministrators("adminGranted", new Object[]{grantedUserId},
                "FEATURE_ENABLEMENT:ADMIN_GRANTED:" + grantedUserId + ":" + now, null);
    }
}
