package com.ses.featureenablement.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.ses.common.enums.FileKind;
import com.ses.common.exception.BusinessException;
import com.ses.config.feature.Feature;
import com.ses.entity.FeatureEnablementRequest;
import com.ses.entity.FeatureSignOff;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.catalog.SignOffKind;
import com.ses.featureenablement.condition.FeatureEvaluationContext;
import com.ses.featureenablement.signoff.FeatureSignOffKindValidator;
import com.ses.featureenablement.state.FeatureEnablementTenant;
import com.ses.mapper.FeatureEnablementRequestMapper;
import com.ses.mapper.FeatureSignOffMapper;
import com.ses.service.FileStorageService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 1件の署名の流れ（feature-enablement-console design §6。FE-RQ-004・017）。
 *
 * <ul>
 *   <li>登録: 文書は {@code FileStorageService.store(byte[], …, FEATURE_SIGN_OFF)} で保存する（scan が CLEAN でなければ保存せず
 *       400。署名の行も作らない）。SHA-256 は bytes から計算する。登録の transaction が rollback すれば保存した文書を消す。</li>
 *   <li>確認: 登録者以外（NF07 の種別は privacy-admin。段階 C）、署名日が今日以前、類の上限、種別の検査。組に属する種別は
 *       1件ずつ確認できない（組の確認だけ）。同じ種別の確認済みがあれば先に SUPERSEDED にしてから CONFIRMED にし（一意索引）、
 *       それを要する開いている開放単位を CHANGE で評価し直す。</li>
 *   <li>取消し: 有効な管理者1名・理由の code。それを要する開いている開放単位を AUTO_CLOSED(SIGN_OFF_REVOKED) にし、全ての管理者へ
 *       通知する。組の member の取消しでも組は CONFIRMED のまま。</li>
 *   <li>延長: 証拠の類の確認済みだけ。申請（{@code SIGN_OFF_EXTENSION}）を申請者以外が承認すると同じ行の失効日を延ばす
 *       （{@link FeatureSignOffExtensionApprover}）。</li>
 *   <li>取得: 専用の経路（{@code loadDedicated}）で読み、大きさと SHA-256 を照合し、取得を監査する。</li>
 *   <li>全ての書込みは世代の行を最初に lock し（失効の先行の閉鎖を含む）、READ COMMITTED で動く。</li>
 * </ul>
 */
@Slf4j
@Service
public class FeatureSignOffServiceImpl implements FeatureSignOffService {

    private final FeatureEnablementWriteSupport write;
    private final FeatureSignOffTransitions transitions;
    private final FeatureSignOffRegistrar registrar;
    private final FeatureEnablementActorPolicy actorPolicy;
    private final FeatureEnablementCatalog catalog;
    private final FeatureSignOffMapper signOffMapper;
    private final FeatureEnablementRequestMapper requestMapper;
    private final FileStorageService fileStorage;
    private final FeatureEnablementTenant tenant;

    public FeatureSignOffServiceImpl(FeatureEnablementWriteSupport write, FeatureSignOffTransitions transitions,
                                     FeatureSignOffRegistrar registrar, FeatureEnablementActorPolicy actorPolicy,
                                     FeatureEnablementCatalog catalog, FeatureSignOffMapper signOffMapper,
                                     FeatureEnablementRequestMapper requestMapper, FileStorageService fileStorage,
                                     FeatureEnablementTenant tenant) {
        this.write = write;
        this.transitions = transitions;
        this.registrar = registrar;
        this.actorPolicy = actorPolicy;
        this.catalog = catalog;
        this.signOffMapper = signOffMapper;
        this.requestMapper = requestMapper;
        this.fileStorage = fileStorage;
        this.tenant = tenant;
    }

    // ---------------------------------------------------------------- 登録

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public FeatureSignOff register(Registration registration, byte[] document, String originalName,
                                   FeatureEnablementActor actor) {
        FeatureEnablementActor checked = requireActor(actor);
        if (registration == null) {
            throw BusinessException.of(400, "error.featureEnablement.signOffKindUnknown");
        }
        SignOffKind kind = FeatureSignOffKindValidator.validate(catalog, input(registration));
        actorPolicy.requirePrivacyAdminIfRequired(kind.privacy(), checked);
        String signerName = FeatureSignOffRegistrar.optionalName(registration.signerName());
        if (!registration.expiresOn().isAfter(FeatureEvaluationContext.businessDate(write.clock()))) {
            // 失効した署名は登録しない（review rb-fe-a1 P3-2）
            throw BusinessException.of(400, "error.featureEnablement.signOffAlreadyExpired");
        }
        // 文書の保存（scan）は世代の lock の前に行う（scan の間に他の書込みを待たせない）
        FeatureSignOffRegistrar.StoredDocument stored = registrar.storeDocument(document, originalName);

        FeatureEnablementWriteSupport.WriteContext context = write.begin(FeatureEnablementWriteSupport.Actor.of(checked));
        FeatureSignOff row = registrar.insertRegistered(context, kind, registration, signerName, stored, checked);
        write.finish(context);
        return row;
    }

    // ---------------------------------------------------------------- 確認（差替え）

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public FeatureSignOff confirm(long id, int expectedVersion, FeatureEnablementActor actor) {
        FeatureEnablementActor checked = requireActor(actor);
        FeatureSignOff found = find(id);
        SignOffKind kind = kindOf(found);
        if (kind.bundle().isPresent()) {
            throw BusinessException.of(409, "error.featureEnablement.signOffBundleOnly");
        }
        actorPolicy.requirePrivacyAdminIfRequired(kind.privacy(), checked);

        FeatureEnablementWriteSupport.WriteContext context = write.begin(FeatureEnablementWriteSupport.Actor.of(checked));
        transitions.lockDependents(context, List.of(kind.code()));
        // lock の順は署名の id の昇順（置き換わる確認済みの行を lock なしで見つけてから、小さい id から lock する）
        FeatureSignOff confirmedNow = signOffMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<FeatureSignOff>()
                        .eq("tenant_id", context.tenantId()).eq("kind_code", kind.code()).eq("status", "CONFIRMED"));
        FeatureSignOff current = null;
        if (confirmedNow != null && confirmedNow.getId() < id) {
            current = signOffMapper.selectConfirmedByKindForUpdate(context.tenantId(), kind.code());
        }
        FeatureSignOff row = lockRow(id, expectedVersion, "REGISTERED", "error.featureEnablement.signOffNotRegistered");
        if (confirmedNow != null && confirmedNow.getId() > id) {
            current = signOffMapper.selectConfirmedByKindForUpdate(context.tenantId(), kind.code());
        }
        verifyConfirmable(context, kind, row, checked);

        if (current != null) {
            transitions.endSignOff(context, current, "SUPERSEDED", null, "REPLACED", row.getId());
        }
        UpdateWrapper<FeatureSignOff> update = new UpdateWrapper<>();
        update.eq("id", row.getId()).eq("status", "REGISTERED").eq("version", row.getVersion())
                .set("status", "CONFIRMED").set("confirmed_by", checked.userId()).set("confirmed_at", context.now())
                .set("version", row.getVersion() + 1);
        if (signOffMapper.update(null, update) != 1) {
            throw BusinessException.of(409, "error.featureEnablement.concurrentChange");
        }
        write.event(context, "SIGN_OFF", kind.code(), row.getId(), "CONFIRMED", "REGISTERED", "CONFIRMED",
                current == null ? null : "REPLACES_" + current.getId(), null);
        write.audit(context, "FEATURE_SIGN_OFF_CONFIRMED", row.getId(),
                "{\"signOff\":" + row.getId() + ",\"kind\":\"" + kind.code() + "\",\"status\":\"REGISTERED\"}",
                "{\"signOff\":" + row.getId() + ",\"kind\":\"" + kind.code() + "\",\"status\":\"CONFIRMED\""
                        + (current == null ? "" : ",\"replaces\":" + current.getId()) + "}");
        context.markChanged(null);
        List<Feature> closed = transitions.reevaluateDependents(context, List.of(kind.code()),
                "SIGN_OFF_REPLACED_INVALID");
        write.finish(context);
        return signOffMapper.selectById(row.getId());
    }

    // ---------------------------------------------------------------- 却下・取下げ・取消し

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void reject(long id, int expectedVersion, String reasonCode, FeatureEnablementActor actor) {
        FeatureEnablementActor checked = requireActor(actor);
        String reason = FeatureEnablementServiceImpl.requiredReasonCode(reasonCode);
        FeatureSignOff found = find(id);
        actorPolicy.requirePrivacyAdminIfRequired(kindOf(found).privacy(), checked);
        FeatureEnablementWriteSupport.WriteContext context = write.begin(FeatureEnablementWriteSupport.Actor.of(checked));
        FeatureSignOff row = lockRow(id, expectedVersion, "REGISTERED", "error.featureEnablement.signOffNotRegistered");
        if (checked.userId().equals(row.getRegisteredBy())) {
            throw BusinessException.of(403, "error.featureEnablement.signOffSelfConfirm");
        }
        transitions.endSignOff(context, row, "REJECTED", checked.userId(), reason, null);
        write.finish(context);
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void withdraw(long id, int expectedVersion, FeatureEnablementActor actor) {
        FeatureEnablementActor checked = requireActor(actor);
        FeatureEnablementWriteSupport.WriteContext context = write.begin(FeatureEnablementWriteSupport.Actor.of(checked));
        FeatureSignOff row = lockRow(id, expectedVersion, "REGISTERED", "error.featureEnablement.signOffNotRegistered");
        if (!checked.userId().equals(row.getRegisteredBy())) {
            throw BusinessException.of(403, "error.featureEnablement.withdrawOnlyRegistrant");
        }
        transitions.endSignOff(context, row, "REJECTED", checked.userId(), "WITHDRAWN_BY_REGISTRANT", null);
        write.finish(context);
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void revoke(long id, int expectedVersion, String reasonCode, FeatureEnablementActor actor) {
        FeatureEnablementActor checked = requireActor(actor);
        String reason = FeatureEnablementServiceImpl.requiredReasonCode(reasonCode);
        FeatureSignOff found = find(id);
        SignOffKind kind = kindOf(found);
        actorPolicy.requirePrivacyAdminIfRequired(kind.privacy(), checked);
        FeatureEnablementWriteSupport.WriteContext context = write.begin(FeatureEnablementWriteSupport.Actor.of(checked));
        transitions.lockDependents(context, List.of(kind.code()));
        FeatureSignOff row = lockRow(id, expectedVersion, "CONFIRMED", "error.featureEnablement.signOffNotConfirmed");
        transitions.endSignOff(context, row, "REVOKED", checked.userId(), reason, null);
        List<Feature> closed = transitions.closeDependents(context, List.of(kind.code()), "SIGN_OFF_REVOKED");
        write.finish(context);
        write.notifier().notifyAdministrators("signOffRevoked", new Object[]{kind.code()},
                "FEATURE_ENABLEMENT:SIGN_OFF_REVOKED:" + row.getId(), List.of());
    }

    // ---------------------------------------------------------------- 延長の申請

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public FeatureEnablementRequest requestExtension(long id, LocalDate newExpiresOn, String reason,
                                                     FeatureEnablementActor actor) {
        FeatureEnablementActor checked = requireActor(actor);
        FeatureSignOff found = find(id);
        SignOffKind kind = kindOf(found);
        actorPolicy.requirePrivacyAdminIfRequired(kind.privacy(), checked);
        String reasonText = reason == null || reason.isBlank() ? null : reason.trim();
        if (reasonText != null && reasonText.length() > FeatureEnablementServiceImpl.REASON_TEXT_MAX) {
            throw BusinessException.of(400, "error.featureEnablement.reasonTooLong",
                    FeatureEnablementServiceImpl.REASON_TEXT_MAX);
        }
        FeatureEnablementWriteSupport.WriteContext context = write.begin(FeatureEnablementWriteSupport.Actor.of(checked));
        String target = EXTENSION_TARGET_PREFIX + id;
        write.expirePending(context, target);
        FeatureSignOff row = signOffMapper.selectById(id);
        if (row == null || !"CONFIRMED".equals(row.getStatus()) || !kind.kindClass().extendable()
                || !"EVIDENCE".equals(row.getKindClass())) {
            throw BusinessException.of(409, "error.featureEnablement.signOffNotExtendable");
        }
        if (!row.getExpiresOn().isAfter(context.today())) {
            // 失効日を過ぎた証拠は延ばさない（日の始まりの EXPIRED の前でも。review rb-fe-a1 P3-2）
            throw BusinessException.of(409, "error.featureEnablement.signOffAlreadyExpired");
        }
        validateExtension(row, newExpiresOn, context.today());

        FeatureEnablementRequest request = new FeatureEnablementRequest();
        request.setTenantId(context.tenantId());
        request.setRequestType(REQUEST_TYPE_EXTENSION);
        request.setTargetKey(target);
        request.setProposedValue(newExpiresOn.toString());
        request.setStatus("PENDING");
        request.setConditionFingerprint(FeatureSignOffExtensionApprover.fingerprint(row, newExpiresOn));
        request.setConditionSummary("signoff." + row.getKindCode() + ".extension=" + newExpiresOn);
        request.setReason(reasonText);
        request.setRequestedBy(checked.userId());
        request.setRequestedAt(context.now());
        request.setExpiresAt(context.now().plus(FeatureEnablementService.REQUEST_TTL));
        request.setVersion(0);
        try {
            requestMapper.insert(request);
        } catch (DuplicateKeyException duplicate) {
            throw BusinessException.of(409, "error.featureEnablement.pendingExists");
        }
        write.event(context, "REQUEST", target, request.getId(), "REQUESTED", null, "PENDING", null,
                request.getConditionSummary());
        write.audit(context, "FEATURE_SIGN_OFF_EXTENSION_REQUESTED", request.getId(), null,
                "{\"signOff\":" + id + ",\"request\":" + request.getId() + ",\"newExpiresOn\":\"" + newExpiresOn + "\"}");
        context.markChanged(null);
        write.finish(context);
        write.notifier().notifyAdministrators("extensionRequested", new Object[]{row.getKindCode(), newExpiresOn},
                "FEATURE_ENABLEMENT:EXTENSION_REQUESTED:" + id + ":" + request.getId(), List.of(checked.userId()));
        return request;
    }

    /** 新しい失効日は今の失効日より後で、基準の日から 1,095 日以内（証拠の類の上限。review P2-N2）。 */
    static void validateExtension(FeatureSignOff row, LocalDate newExpiresOn, LocalDate base) {
        if (newExpiresOn == null || !newExpiresOn.isAfter(row.getExpiresOn())) {
            throw BusinessException.of(400, "error.featureEnablement.signOffExtensionInvalid",
                    com.ses.featureenablement.catalog.SignOffKindClass.EVIDENCE.maxValidityDays());
        }
        if (ChronoUnit.DAYS.between(base, newExpiresOn)
                > com.ses.featureenablement.catalog.SignOffKindClass.EVIDENCE.maxValidityDays()) {
            throw BusinessException.of(400, "error.featureEnablement.signOffExtensionInvalid",
                    com.ses.featureenablement.catalog.SignOffKindClass.EVIDENCE.maxValidityDays());
        }
    }

    // ---------------------------------------------------------------- 読取り

    @Override
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public List<Summary> list(FeatureEnablementActor actor) {
        requireActor(actor);
        return signOffMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<FeatureSignOff>()
                        .eq("tenant_id", tenant.tenantId()).orderByAsc("kind_code").orderByDesc("id"))
                .stream().map(FeatureSignOffServiceImpl::summary).toList();
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public FeatureSignOff detail(long id, FeatureEnablementActor actor) {
        requireActor(actor);
        return find(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Document document(long id, FeatureEnablementActor actor) {
        FeatureEnablementActor checked = requireActor(actor);
        FeatureSignOff row = find(id);
        if (row.getStoredName() == null || "PURGED".equals(row.getStatus())) {
            throw BusinessException.of(409, "error.featureEnablement.signOffDocumentPurged");
        }
        byte[] content = AccountingTenantContextHolder.runWithTenant(row.getTenantId(), () -> read(row.getStoredName()));
        if (row.getSizeBytes() == null || content.length != row.getSizeBytes()
                || !FeatureSignOffRegistrar.sha256(content).equals(row.getContentSha256())) {
            log.warn("[機能の開放] 署名の文書の大きさか SHA-256 が登録と違います: errorCode=FEATURE_SIGN_OFF_DOCUMENT_MISMATCH "
                    + "signOff={}", row.getId());
            throw BusinessException.of(409, "error.featureEnablement.signOffDocumentMismatch");
        }
        write.auditRead(checked, "FEATURE_SIGN_OFF_DOCUMENT_READ", row.getId(),
                "{\"signOff\":" + row.getId() + ",\"kind\":\"" + row.getKindCode() + "\",\"sha256\":\""
                        + row.getContentSha256() + "\"}");
        String fileName = row.getKindCode() + "-" + row.getId() + ".pdf";
        return new Document(fileName, content, row.getContentSha256());
    }

    // ---------------------------------------------------------------- helper

    private void verifyConfirmable(FeatureEnablementWriteSupport.WriteContext context, SignOffKind kind,
                                   FeatureSignOff row, FeatureEnablementActor confirmer) {
        if (confirmer.userId().equals(row.getRegisteredBy())) {
            throw BusinessException.of(403, "error.featureEnablement.signOffSelfConfirm");
        }
        if (!row.getExpiresOn().isAfter(context.today())) {
            // 失効した署名で確認済みの署名を差し替えない（依存する開放単位が閉じる誤操作を防ぐ。review rb-fe-a1 P3-2）
            throw BusinessException.of(400, "error.featureEnablement.signOffAlreadyExpired");
        }
        if (row.getSignedOn().isAfter(context.today())) {
            throw BusinessException.of(400, "error.featureEnablement.signOffNotYetSigned");
        }
        FeatureSignOffKindValidator.validate(catalog, new FeatureSignOffKindValidator.Input(row.getKindCode(),
                row.getReference(), row.getSignerRole(), row.getSignedOn(), row.getExpiresOn(),
                FeatureSignOffRegistrar.structuredMap(row.getStructuredValues())));
        if (!kind.kindClass().name().equals(row.getKindClass())) {
            throw BusinessException.of(409, "error.featureEnablement.signOffKindUnknown");
        }
    }

    private FeatureSignOff lockRow(long id, int expectedVersion, String expectedStatus, String statusMessage) {
        FeatureSignOff row = signOffMapper.selectByIdForUpdate(id);
        if (row == null || !tenant.tenantId().equals(row.getTenantId())) {
            throw BusinessException.of(404, "error.featureEnablement.signOffNotFound");
        }
        if (!expectedStatus.equals(row.getStatus())) {
            throw BusinessException.of(409, statusMessage);
        }
        if (row.getVersion() == null || row.getVersion() != expectedVersion) {
            throw BusinessException.of(409, "error.featureEnablement.signOffVersionMismatch");
        }
        return row;
    }

    private FeatureSignOff find(long id) {
        FeatureSignOff row = signOffMapper.selectById(id);
        if (row == null || !tenant.tenantId().equals(row.getTenantId())) {
            throw BusinessException.of(404, "error.featureEnablement.signOffNotFound");
        }
        return row;
    }

    private SignOffKind kindOf(FeatureSignOff row) {
        return catalog.kind(row.getKindCode())
                .orElseThrow(() -> BusinessException.of(409, "error.featureEnablement.signOffKindUnknown"));
    }

    private FeatureEnablementActor requireActor(FeatureEnablementActor actor) {
        return actorPolicy.requireActiveAdministrator(actor == null ? null : actor.userId());
    }

    private Resource load(String storedName) {
        return fileStorage.loadDedicated(storedName, FileKind.FEATURE_SIGN_OFF);
    }

    private byte[] read(String storedName) {
        try (InputStream in = load(storedName).getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw BusinessException.of(409, "error.featureEnablement.signOffDocumentMismatch");
        }
    }

    private static FeatureSignOffKindValidator.Input input(Registration registration) {
        return new FeatureSignOffKindValidator.Input(registration.kindCode(), registration.reference(),
                registration.signerRole(), registration.signedOn(), registration.expiresOn(),
                registration.structuredValues());
    }

    private static Summary summary(FeatureSignOff row) {
        return new Summary(row.getId(), row.getKindCode(), row.getKindClass(), row.getReference(), row.getSignerRole(),
                row.getSignedOn(), row.getExpiresOn(), row.getStatus(), row.getVersion() == null ? 0 : row.getVersion(),
                row.getRegisteredBy(), row.getRegisteredAt(), row.getConfirmedBy(), row.getConfirmedAt(),
                row.getEndReason(), row.getContentSha256() == null ? null : row.getContentSha256().substring(0, 16));
    }
}
