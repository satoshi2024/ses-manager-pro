package com.ses.featureenablement.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.FeatureEnablementRequest;
import com.ses.entity.FeatureSignOff;
import com.ses.entity.FeatureSignOffExtension;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.catalog.SignOffKind;
import com.ses.featureenablement.catalog.SignOffKindClass;
import com.ses.mapper.FeatureEnablementRequestMapper;
import com.ses.mapper.FeatureSignOffExtensionMapper;
import com.ses.mapper.FeatureSignOffMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;

/**
 * 証拠の類の署名の延長の申請（{@code SIGN_OFF_EXTENSION}）の承認（feature-enablement-console design §2.6.2・§6.5。review P2-N2）。
 *
 * <ul>
 *   <li>{@link FeatureEnablementServiceImpl#approve} が世代・申請の行を lock し、申請者本人の承認を 403 にした後に呼ぶ。続けて署名の
 *       行を lock する（lock の順 (3) → (4)）。</li>
 *   <li>承認の時の指紋は対象の署名の {@code id:version}・状態・類・失効日と新しい失効日から計算し、申請の時の指紋と違えば申請を
 *       STALE で終える（spec の micro review P3-M1）。署名がまだ {@code CONFIRMED} で証拠の類であることも確かめる。</li>
 *   <li>申請を先に APPROVED にしてから（延長の記録の複合 FK が申請の申請者・決定者を参照する）、同じ行の失効日を更新し、追記専用の
 *       {@code t_feature_sign_off_extension} に1行を足す。文書・保存名・署名日は変えない。新しい失効日の上限は承認の日から
 *       1,095 日。</li>
 *   <li>NF07 の種別は申請者・承認者とも privacy-admin（J-05。段階 C で有効にする）。</li>
 * </ul>
 */
@Component
public class FeatureSignOffExtensionApprover implements FeatureEnablementRequestApprover {

    /** 指紋の版（入力の並びを変えたら上げる）。 */
    static final String FINGERPRINT_VERSION = "fe-extension-v1";

    private final FeatureEnablementWriteSupport write;
    private final FeatureEnablementActorPolicy actorPolicy;
    private final FeatureEnablementCatalog catalog;
    private final FeatureSignOffMapper signOffMapper;
    private final FeatureSignOffExtensionMapper extensionMapper;
    private final FeatureEnablementRequestMapper requestMapper;

    public FeatureSignOffExtensionApprover(FeatureEnablementWriteSupport write, FeatureEnablementActorPolicy actorPolicy,
                                           FeatureEnablementCatalog catalog, FeatureSignOffMapper signOffMapper,
                                           FeatureSignOffExtensionMapper extensionMapper,
                                           FeatureEnablementRequestMapper requestMapper) {
        this.write = write;
        this.actorPolicy = actorPolicy;
        this.catalog = catalog;
        this.signOffMapper = signOffMapper;
        this.extensionMapper = extensionMapper;
        this.requestMapper = requestMapper;
    }

    @Override
    public String requestType() {
        return FeatureSignOffService.REQUEST_TYPE_EXTENSION;
    }

    @Override
    public FeatureEnablementApprovalResult approveLocked(FeatureEnablementWriteSupport.WriteContext context,
                                                         FeatureEnablementRequest request, FeatureEnablementActor approver,
                                                         String decisionReason) {
        long signOffId = signOffId(request.getTargetKey());
        LocalDate newExpiresOn = parseDate(request.getProposedValue());
        FeatureSignOff row = signOffMapper.selectByIdForUpdate(signOffId);
        SignOffKind kind = row == null ? null : catalog.kind(row.getKindCode()).orElse(null);
        if (kind != null) {
            actorPolicy.requirePrivacyAdminIfRequired(kind.privacy(), approver);
        }
        if (row == null || kind == null || !context.tenantId().equals(row.getTenantId())
                || !"CONFIRMED".equals(row.getStatus()) || !SignOffKindClass.EVIDENCE.name().equals(row.getKindClass())
                || !fingerprint(row, newExpiresOn).equals(request.getConditionFingerprint())) {
            write.endBySystem(context, request, "STALE", "FEATURE_SIGN_OFF_EXTENSION_STALE", "SIGN_OFF_CHANGED");
            return new FeatureEnablementApprovalResult(FeatureEnablementApprovalResult.Outcome.STALE, request.getId(),
                    List.of());
        }
        if (!newExpiresOn.isAfter(row.getExpiresOn())
                || ChronoUnit.DAYS.between(context.today(), newExpiresOn) > SignOffKindClass.EVIDENCE.maxValidityDays()) {
            return new FeatureEnablementApprovalResult(FeatureEnablementApprovalResult.Outcome.CONDITIONS_UNSATISFIED,
                    request.getId(), List.of("signoff." + row.getKindCode() + ".extensionTooLong"));
        }

        // 申請を先に APPROVED にする（延長の記録の複合 FK）
        UpdateWrapper<FeatureEnablementRequest> decided = new UpdateWrapper<>();
        decided.eq("id", request.getId()).eq("status", "PENDING").eq("version", request.getVersion())
                .set("status", "APPROVED").set("decided_by", approver.userId()).set("decided_at", context.now())
                .set("decision_reason", decisionReason).set("version", request.getVersion() + 1);
        if (requestMapper.update(null, decided) != 1) {
            throw BusinessException.of(409, "error.featureEnablement.concurrentChange");
        }
        UpdateWrapper<FeatureSignOff> extended = new UpdateWrapper<>();
        extended.eq("id", row.getId()).eq("status", "CONFIRMED").eq("version", row.getVersion())
                .set("expires_on", newExpiresOn).set("version", row.getVersion() + 1);
        if (signOffMapper.update(null, extended) != 1) {
            throw BusinessException.of(409, "error.featureEnablement.concurrentChange");
        }
        FeatureSignOffExtension extension = new FeatureSignOffExtension();
        extension.setTenantId(context.tenantId());
        extension.setSignOffId(row.getId());
        extension.setRequestId(request.getId());
        extension.setOldExpiresOn(row.getExpiresOn());
        extension.setNewExpiresOn(newExpiresOn);
        extension.setRequestedBy(request.getRequestedBy());
        extension.setConfirmedBy(approver.userId());
        extension.setConfirmedAt(context.now());
        extensionMapper.insert(extension);
        write.event(context, "SIGN_OFF", row.getKindCode(), row.getId(), "EXTENDED", "CONFIRMED", "CONFIRMED",
                "REQUEST_" + request.getId(), null);
        write.audit(context, "FEATURE_SIGN_OFF_EXTENDED", row.getId(),
                "{\"signOff\":" + row.getId() + ",\"expiresOn\":\"" + row.getExpiresOn() + "\"}",
                "{\"signOff\":" + row.getId() + ",\"expiresOn\":\"" + newExpiresOn + "\",\"request\":" + request.getId()
                        + "}");
        context.markChanged(null);
        return FeatureEnablementApprovalResult.approved(request.getId());
    }

    /** 延長の指紋（対象の署名の {@code id:version}・状態・類・失効日と新しい失効日。P3-M1）。 */
    public static String fingerprint(FeatureSignOff row, LocalDate newExpiresOn) {
        String canonical = String.join("\n", FINGERPRINT_VERSION, FeatureSignOffService.REQUEST_TYPE_EXTENSION,
                FeatureSignOffService.EXTENSION_TARGET_PREFIX + row.getId(), row.getId() + ":" + row.getVersion(),
                String.valueOf(row.getStatus()), String.valueOf(row.getKindClass()), String.valueOf(row.getExpiresOn()),
                String.valueOf(newExpiresOn));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 を使えません", e);
        }
    }

    private static long signOffId(String targetKey) {
        if (targetKey == null || !targetKey.startsWith(FeatureSignOffService.EXTENSION_TARGET_PREFIX)) {
            throw BusinessException.of(409, "error.featureEnablement.requestTypeUnsupported");
        }
        try {
            return Long.parseLong(targetKey.substring(FeatureSignOffService.EXTENSION_TARGET_PREFIX.length()));
        } catch (NumberFormatException e) {
            throw BusinessException.of(409, "error.featureEnablement.requestTypeUnsupported");
        }
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException | NullPointerException e) {
            throw BusinessException.of(409, "error.featureEnablement.requestTypeUnsupported");
        }
    }
}
