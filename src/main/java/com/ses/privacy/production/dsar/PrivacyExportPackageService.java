package com.ses.privacy.production.dsar;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.enums.FileKind;
import com.ses.config.OidcSecurityProperties;
import com.ses.dto.file.StoredFile;
import com.ses.entity.privacy.PrivacyAction;
import com.ses.entity.privacy.PrivacyExportPackage;
import com.ses.entity.privacy.PrivacyRequest;
import com.ses.entity.privacy.PrivacyRequestSubjectLink;
import com.ses.mapper.privacy.PrivacyActionMapper;
import com.ses.mapper.privacy.PrivacyExportPackageMapper;
import com.ses.mapper.privacy.PrivacyRequestMapper;
import com.ses.mapper.privacy.PrivacyRequestSubjectLinkMapper;
import com.ses.privacy.foundation.PrivacyCaseState;
import com.ses.privacy.production.PrivacyAccessGuard;
import com.ses.privacy.production.PrivacyDecisionBinding;
import com.ses.privacy.production.ProductionPrivacyProviderRegistry;
import com.ses.privacy.production.provider.PrivacyRecord;
import com.ses.privacy.production.provider.PrivacySubjectRef;
import com.ses.privacy.production.redaction.ThirdPartyRedactor;
import com.ses.privacy.production.retention.hold.PrivacyHoldGate;
import com.ses.service.FileStorageService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * DSAR の検索〜交付記録（NF07-A2b、production-bridge-phased-plan.md §3.5・§3.5.2・§3.1.5 の承認の束縛）。
 *
 * <p>状態遷移（開示請求 ACCESS だけ。訂正・削除・利用停止・異議は A2a の終了の記録だけ）:
 * <ol>
 *   <li>草案（SCOPED → PREVIEWED、{@link #preview}）: D-01・D-06 が有効で、package の最長保存期間（{@code privacy.dsar.package-max-age}）が
 *       設定され、本人確認で適用した D-06 が現在と同じで、範囲 snapshot を DB の値だけで再計算して一致するときだけ作る。
 *       {@link PrivacySearchService} で F2 provider を検索し、{@link ThirdPartyRedactor} を通した package（{@link PrivacyExportPackageContent}）を
 *       {@link FileStorageService}（quarantine → scan → published）へ保存し、DRAFT の package 行と検索・要手動確認の
 *       {@code t_privacy_action} 行を書く。作成者は {@code prepared_by} と package の {@code created_by}。期限切れの package しか
 *       無い PREVIEWED・APPROVED の case は同じ操作で作り直す（承認は外れる）。</li>
 *   <li>承認（PREVIEWED → APPROVED、{@link #approve}）: 作成者以外の privacy-admin（{@code privacy.dsar.approve}）が、画面で確認した
 *       package を id と内容 hash で特定して承認する。作成者≠承認者は service（403）と CAS の条件と DB の CHECK
 *       （{@code chk_privacy_request_sod}・{@code chk_privacy_export_package_sod}）の全てで強制する。同じ transaction で package を
 *       APPROVED にし、request に {@code approved_package_id}・{@code approved_content_sha256} を書く（§3.1.5）。</li>
 *   <li>専用ダウンロード（{@link #download}、{@code privacy.dsar.export}）: case が APPROVED、要求の package id が
 *       {@code approved_package_id} と一致、package が APPROVED で期限内、package と request の内容 hash が一致し、保存実体の
 *       hash と大きさも一致するときだけ返す（実体は {@code PUBLISHED}+{@code CLEAN} の file だけを {@link FileStorageService#load} が開く）。
 *       汎用の {@code /api/files/**} からは取得できない（{@code FileScopeValidationService}）。</li>
 *   <li>交付記録（APPROVED → CONFIRMED、{@link #recordDelivery}）: system の外で交付した後に、方法と証跡の参照を、承認した package の
 *       id と hash の照合付きで記録する。同じ transaction で package を PURGED にし、実体を消す（消せなければ記録ごと巻き戻す）。</li>
 *   <li>削除（{@link #purgeDue}、scheduler）: {@code purge_after} を過ぎた package と、終了状態の case の package を PURGED にして実体を消す。</li>
 * </ol>
 * system から外部へ送る経路は持たない（I-4）。監査は同じ transaction で必須記録し、本文は受付番号と状態遷移だけにする
 * （保存名・内容 hash・subject 参照を入れない）。状態を変える操作は request 行の「現状態・{@code row_version}」の CAS を先に通す。
 */
public class PrivacyExportPackageService {

    private static final Logger log = LoggerFactory.getLogger(PrivacyExportPackageService.class);

    static final String APPROVE_ACTION = "privacy.dsar.approve";
    static final String EXPORT_ACTION = "privacy.dsar.export";

    static final String PACKAGE_DRAFT = "DRAFT";
    static final String PACKAGE_APPROVED = "APPROVED";
    static final String PACKAGE_PURGED = "PURGED";
    static final String ACTION_TYPE_SEARCH = "SEARCH";
    static final String ACTION_TYPE_EXPORT = "EXPORT_DRAFT";
    static final String ACTION_STATUS_SUCCEEDED = "SUCCEEDED";
    /** 要手動確認の行の状態（自動では処理しなかった）。 */
    static final String ACTION_STATUS_MANUAL = "BLOCKED";
    static final String SEARCH_KEY_PREFIX = "DSAR-SEARCH:";
    static final String EXPORT_KEY_PREFIX = "DSAR-EXPORT:";
    /** scheduler の削除を監査に記録する username。 */
    static final String PURGE_USERNAME = "system";
    /** 1回の削除で扱う package の上限（残りは次の回）。 */
    static final int PURGE_BATCH = 200;
    static final String LOG_CODE_PURGE_FAILED = "NF07_DSAR_PACKAGE_PURGE_FAILED";
    static final String LOG_CODE_FILE_CLEANUP_FAILED = "NF07_DSAR_PACKAGE_FILE_CLEANUP_FAILED";
    static final String PACKAGE_FILE_NAME = "dsar-export-package.json";

    static final String ERROR_PACKAGE_RULE_UNSET = "error.privacy.dsarPackageRuleUnset";
    static final String ERROR_EXPORT_ACCESS_ONLY = "error.privacy.dsarExportAccessOnly";
    static final String ERROR_SCOPE_CHANGED = "error.privacy.dsarScopeChanged";
    static final String ERROR_APPROVER_IS_PREPARER = "error.privacy.dsarApproverIsPreparer";
    static final String ERROR_PACKAGE_MISMATCH = "error.privacy.dsarPackageMismatch";
    static final String ERROR_PACKAGE_NOT_APPROVED = "error.privacy.dsarPackageNotApproved";
    static final String ERROR_PACKAGE_EXPIRED = "error.privacy.dsarPackageExpired";
    static final String ERROR_PACKAGE_INTEGRITY = "error.privacy.dsarPackageIntegrity";
    static final String ERROR_PACKAGE_FILE_UNAVAILABLE = "error.privacy.dsarPackageFileUnavailable";

    static final String HOLD_NONE = "NONE";
    static final String HOLD_HELD = "HELD";
    static final String HOLD_NOT_HELD = "NOT_HELD";
    static final String HOLD_UNKNOWN = "UNKNOWN";
    static final String HOLD_NOT_SCOPED = "NOT_SCOPED";

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    /** 草案を作れる（作り直せる）状態。PREVIEWED 以降は package が期限切れのときだけ。 */
    private static final Set<PrivacyCaseState> PREVIEWABLE = EnumSet.of(PrivacyCaseState.SCOPED,
            PrivacyCaseState.PREVIEWED, PrivacyCaseState.APPROVAL_PENDING, PrivacyCaseState.APPROVED);
    private static final Set<PrivacyCaseState> APPROVABLE = EnumSet.of(PrivacyCaseState.PREVIEWED,
            PrivacyCaseState.APPROVAL_PENDING);

    private final PrivacyAccessGuard accessGuard;
    private final OidcSecurityProperties oidcProperties;
    private final PrivacyDsarDecisions decisions;
    private final PrivacyRequestMapper requestMapper;
    private final PrivacyRequestSubjectLinkMapper linkMapper;
    private final PrivacyActionMapper actionMapper;
    private final PrivacyExportPackageMapper packageMapper;
    private final PrivacySearchService searchService;
    private final ProductionPrivacyProviderRegistry registry;
    private final ThirdPartyRedactor redactor;
    private final FileStorageService fileStorage;
    private final Supplier<PrivacyHoldGate> holdGate;
    private final PrivacyDsarAudit audit;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Supplier<PrivacyRequestService.Actor> actorSource;

    PrivacyExportPackageService(PrivacyAccessGuard accessGuard, OidcSecurityProperties oidcProperties,
                                PrivacyDsarDecisions decisions, PrivacyRequestMapper requestMapper,
                                PrivacyRequestSubjectLinkMapper linkMapper, PrivacyActionMapper actionMapper,
                                PrivacyExportPackageMapper packageMapper, PrivacySearchService searchService,
                                ProductionPrivacyProviderRegistry registry, ThirdPartyRedactor redactor,
                                FileStorageService fileStorage, Supplier<PrivacyHoldGate> holdGate,
                                PrivacyDsarAudit audit, TransactionTemplate transactions, Clock clock,
                                Supplier<PrivacyRequestService.Actor> actorSource) {
        this.accessGuard = Objects.requireNonNull(accessGuard, "accessGuard");
        this.oidcProperties = oidcProperties;
        this.decisions = Objects.requireNonNull(decisions, "decisions");
        this.requestMapper = Objects.requireNonNull(requestMapper, "requestMapper");
        this.linkMapper = Objects.requireNonNull(linkMapper, "linkMapper");
        this.actionMapper = Objects.requireNonNull(actionMapper, "actionMapper");
        this.packageMapper = Objects.requireNonNull(packageMapper, "packageMapper");
        this.searchService = Objects.requireNonNull(searchService, "searchService");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.redactor = Objects.requireNonNull(redactor, "redactor");
        this.fileStorage = Objects.requireNonNull(fileStorage, "fileStorage");
        this.holdGate = holdGate == null ? () -> null : holdGate;
        this.audit = Objects.requireNonNull(audit, "audit");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.actorSource = actorSource == null ? PrivacyRequestService.Actor::fromSecurityContext : actorSource;
    }

    // ---------------------------------------------------------------- 照会

    /** 開示資料の区画（package の要約・要手動確認・hold の状態・行える操作）。 */
    public PrivacyDsarViews.DeliverySection section(long requestId) {
        Context context = begin(PrivacyRequestService.VIEW_ACTION);
        PrivacyRequest request = load(context.tenantId(), requestId);
        PrivacyCaseState state = PrivacyDsarVocabulary.state(request.getStatus()).orElse(null);
        PrivacyDsarDecisions.Readiness readiness = decisions.evaluate();
        boolean packageRule = decisions.packageMaxAge() != null;
        boolean decisionChanged = readiness.ready() && request.getIdentityRuleRef() != null
                && !request.getIdentityRuleRef().equals(readiness.d06DecisionId())
                && state != null && !PrivacyDsarVocabulary.TERMINAL_STATES.contains(state);
        boolean access = PrivacyDsarVocabulary.RequestType.ACCESS.name().equals(request.getRequestType());
        LocalDateTime now = now();
        Long me = context.actor().userId();
        PrivacyExportPackage current = latestPackage(context.tenantId(), requestId);
        PrivacyDsarViews.PackageSummary summary = current == null ? null : new PrivacyDsarViews.PackageSummary(
                current.getId(), current.getStatus(), current.getContentSha256(),
                current.getSizeBytes() == null ? 0L : current.getSizeBytes(), display(current.getCreatedAt()),
                display(current.getPurgeAfter()), !PACKAGE_PURGED.equals(current.getStatus()) && expired(current, now),
                me != null && me.equals(current.getCreatedBy()));

        PrivacyAction export = latestExportRow(context.tenantId(), requestId);
        List<PrivacyDsarViews.ProviderCount> providers = new ArrayList<>();
        List<PrivacyDsarViews.ManualReviewItem> manual = new ArrayList<>();
        if (export != null) {
            for (PrivacyAction row : searchRows(context.tenantId(), requestId, versionOf(export))) {
                if (ACTION_STATUS_SUCCEEDED.equals(row.getStatus())) {
                    providers.add(new PrivacyDsarViews.ProviderCount(row.getProviderId(),
                            row.getResultCount() == null ? 0 : row.getResultCount()));
                } else {
                    manual.add(new PrivacyDsarViews.ManualReviewItem(row.getProviderId(), row.getDataElementId(),
                            row.getBlockedReason(), row.getResultCount()));
                }
            }
        }
        PrivacyDsarViews.DeliveryRecord delivery = PrivacyCaseState.CONFIRMED == state
                ? new PrivacyDsarViews.DeliveryRecord(display(request.getDeliveredAt()), request.getDeliveryMethod(),
                request.getDeliveryEvidenceRef(), me != null && me.equals(request.getDeliveredBy()))
                : null;
        boolean preparedByMe = me != null && me.equals(request.getPreparedBy());
        return new PrivacyDsarViews.DeliverySection(request.getStatus(), version(request), access,
                readiness.ready() && packageRule && !decisionChanged, packageRule, decisionChanged,
                holdStatus(context.tenantId(), requestId), summary, preparedByMe,
                me != null && me.equals(request.getApprovedBy()), display(request.getApprovedAt()),
                export == null ? null : export.getEvidenceRef(), export == null ? null : export.getResultCount(),
                providers, manual, delivery, actions(state, access, request, current, summary, me));
    }

    // ---------------------------------------------------------------- 草案

    /** 検索と草案 package の作成（SCOPED → PREVIEWED。期限切れの package しか無い case の作り直しも同じ）。 */
    public PrivacyDsarViews.Transition preview(long requestId, PrivacyDsarCommands.Preview command) {
        Context context = beginManage(PrivacyRequestService.MANAGE_ACTION);
        PrivacyDsarDecisions.Readiness readiness = requireDecisions();
        Duration maxAge = decisions.packageMaxAge();
        if (maxAge == null) {
            // D-06 の最長保存期間が無ければ草案を作らない（§5.2）
            throw BusinessException.of(409, ERROR_PACKAGE_RULE_UNSET);
        }
        long version = requireVersion(command == null ? null : command.rowVersion());
        LocalDateTime now = now();
        // 記録を読む前に lock を取らない事前確認で状態・版・前提を確かめる。確定は下の transaction の CAS が行う。
        PrivacyRequest request = load(context.tenantId(), requestId);
        requirePreviewable(request, version);
        PrivacyRequestService.requireSameIdentityRule(request, readiness);
        PrivacySubjectRef subject = selectedSubject(context.tenantId(), requestId);
        PrivacyAction scopeRow = verifyScopeSnapshot(context.tenantId(), request, subject);
        PrivacySearchService.SearchResult search = searchService.search(context.tenantId(), subject);
        PrivacyExportPackageContent.Built built = PrivacyExportPackageContent.build(
                new PrivacyExportPackageContent.Header(request.getRequestNo(), request.getRequestType(), subject.ref(),
                        request.getIdentityRuleRef(), scopeRow.getCreatedAt(), now),
                search, redactor, registry);
        String storedName = store(built);
        try {
            return execute(() -> {
                PrivacyRequest locked = load(context.tenantId(), requestId);
                PrivacyCaseState from = requirePreviewable(locked, version);
                purgeExpiredOf(context.tenantId(), locked, now, context.actor().username());
                LocalDate backfilledDueDate = PrivacyRequestService.backfilledDueDate(locked, readiness);
                cas(context.tenantId(), requestId, from, version, now, update -> update
                        .set(backfilledDueDate != null, PrivacyRequest::getDueDate, backfilledDueDate)
                        .set(PrivacyRequest::getStatus, PrivacyCaseState.PREVIEWED.name())
                        .set(PrivacyRequest::getPreparedBy, context.actor().userId())
                        .set(PrivacyRequest::getApprovedBy, null)
                        .set(PrivacyRequest::getApprovedAt, null)
                        .set(PrivacyRequest::getApprovedPackageId, null)
                        .set(PrivacyRequest::getApprovedContentSha256, null));
                PrivacyExportPackage row = new PrivacyExportPackage();
                row.setTenantId(context.tenantId());
                row.setRequestId(requestId);
                row.setStoredName(storedName);
                row.setContentSha256(built.sha256());
                row.setSizeBytes((long) built.bytes().length);
                row.setStatus(PACKAGE_DRAFT);
                row.setPurgeAfter(now.plus(maxAge).truncatedTo(ChronoUnit.MICROS));
                row.setCreatedBy(context.actor().userId());
                row.setCreatedAt(now.truncatedTo(ChronoUnit.MICROS));
                try {
                    packageMapper.insert(row);
                } catch (DuplicateKeyException e) {
                    throw conflict();
                }
                writeSearchRows(context.tenantId(), requestId, version + 1, search, built, now);
                audit.transition(context.actor().username(), PrivacyDsarAudit.PREVIEWED, locked.getRequestNo(),
                        from.name(), PrivacyCaseState.PREVIEWED.name());
                return new PrivacyDsarViews.Transition(requestId, locked.getRequestNo(),
                        PrivacyCaseState.PREVIEWED.name(), version + 1);
            });
        } catch (RuntimeException e) {
            deleteQuietly(storedName);
            throw e;
        }
    }

    // ---------------------------------------------------------------- 承認

    /** 作成者以外の privacy-admin による承認（PREVIEWED → APPROVED）。 */
    public PrivacyDsarViews.Transition approve(long requestId, PrivacyDsarCommands.Approval command) {
        Context context = beginManage(APPROVE_ACTION);
        PrivacyDsarDecisions.Readiness readiness = requireDecisions();
        long version = requireVersion(command == null ? null : command.rowVersion());
        long packageId = requirePositive(command.packageId());
        String sha = requireSha(command.contentSha256());
        Long approver = context.actor().userId();
        LocalDateTime now = now();
        PrivacyRequest request = load(context.tenantId(), requestId);
        requireApprovable(request, version, approver);
        PrivacyRequestService.requireSameIdentityRule(request, readiness);
        PrivacyExportPackage target = requireDraft(context.tenantId(), requestId, packageId, sha, approver, now);
        // 承認するのは保存されている実体そのもの（内容 hash と大きさが一致しなければ承認しない）
        readVerified(target);
        return execute(() -> {
            PrivacyRequest locked = load(context.tenantId(), requestId);
            PrivacyCaseState from = requireApprovable(locked, version, approver);
            requireDraft(context.tenantId(), requestId, packageId, sha, approver, now);
            LocalDateTime approvedAt = now.truncatedTo(ChronoUnit.MICROS);
            // 作成者≠承認者を CAS の条件にも入れる（DB の CHECK chk_privacy_request_sod と同じ条件）
            cas(context.tenantId(), requestId, from, version, now, update -> update
                    .isNotNull(PrivacyRequest::getPreparedBy)
                    .ne(PrivacyRequest::getPreparedBy, approver)
                    .set(PrivacyRequest::getStatus, PrivacyCaseState.APPROVED.name())
                    .set(PrivacyRequest::getApprovedBy, approver)
                    .set(PrivacyRequest::getApprovedAt, approvedAt)
                    .set(PrivacyRequest::getApprovedPackageId, packageId)
                    .set(PrivacyRequest::getApprovedContentSha256, sha));
            int updated = packageMapper.update(null, new LambdaUpdateWrapper<PrivacyExportPackage>()
                    .eq(PrivacyExportPackage::getTenantId, context.tenantId())
                    .eq(PrivacyExportPackage::getRequestId, requestId)
                    .eq(PrivacyExportPackage::getId, packageId)
                    .eq(PrivacyExportPackage::getStatus, PACKAGE_DRAFT)
                    .eq(PrivacyExportPackage::getContentSha256, sha)
                    .ne(PrivacyExportPackage::getCreatedBy, approver)
                    .set(PrivacyExportPackage::getStatus, PACKAGE_APPROVED)
                    .set(PrivacyExportPackage::getApprovedBy, approver)
                    .set(PrivacyExportPackage::getApprovedAt, approvedAt));
            if (updated != 1) {
                throw conflict();
            }
            audit.transition(context.actor().username(), PrivacyDsarAudit.APPROVED, locked.getRequestNo(), from.name(),
                    PrivacyCaseState.APPROVED.name());
            return new PrivacyDsarViews.Transition(requestId, locked.getRequestNo(), PrivacyCaseState.APPROVED.name(),
                    version + 1);
        });
    }

    // ---------------------------------------------------------------- 専用ダウンロード

    /** 承認した package の専用ダウンロード（{@code GET /api/privacy/dsar/{id}/package/download?packageId=}）。 */
    public PrivacyDsarViews.PackageDownload download(long requestId, Long packageId) {
        Context context = beginManage(EXPORT_ACTION);
        long requested = requirePositive(packageId);
        PrivacyRequest request = load(context.tenantId(), requestId);
        PrivacyExportPackage approved = requireApprovedPackage(context.tenantId(), request, requested, false);
        byte[] content = readVerified(approved);
        execute(() -> {
            audit.event(context.actor().username(), PrivacyDsarAudit.PACKAGE_DOWNLOADED, request.getRequestNo());
            return null;
        });
        return new PrivacyDsarViews.PackageDownload(request.getRequestNo() + ".json", content);
    }

    // ---------------------------------------------------------------- 交付記録

    /** system の外での交付の記録（APPROVED → CONFIRMED。package は同時に PURGED）。 */
    public PrivacyDsarViews.Transition recordDelivery(long requestId, PrivacyDsarCommands.Delivery command) {
        Context context = beginManage(PrivacyRequestService.MANAGE_ACTION);
        long version = requireVersion(command == null ? null : command.rowVersion());
        long packageId = requirePositive(command.packageId());
        String sha = requireSha(command.contentSha256());
        PrivacyDsarVocabulary.DeliveryMethod method = PrivacyDsarVocabulary
                .parse(PrivacyDsarVocabulary.DeliveryMethod.class, command.deliveryMethod())
                .orElseThrow(PrivacyExportPackageService::invalid);
        String evidenceRef = requireReference(command.deliveryEvidenceRef());
        PrivacyRequest request = load(context.tenantId(), requestId);
        requireDeliverable(context.tenantId(), request, version, packageId, sha);
        return execute(() -> {
            PrivacyRequest locked = load(context.tenantId(), requestId);
            PrivacyExportPackage approved = requireDeliverable(context.tenantId(), locked, version, packageId, sha);
            LocalDateTime now = now();
            LocalDateTime deliveredAt = now.truncatedTo(ChronoUnit.MICROS);
            cas(context.tenantId(), requestId, PrivacyCaseState.APPROVED, version, now, update -> update
                    .eq(PrivacyRequest::getApprovedPackageId, packageId)
                    .eq(PrivacyRequest::getApprovedContentSha256, sha)
                    .set(PrivacyRequest::getStatus, PrivacyCaseState.CONFIRMED.name())
                    .set(PrivacyRequest::getDeliveredBy, context.actor().userId())
                    .set(PrivacyRequest::getDeliveredAt, deliveredAt)
                    .set(PrivacyRequest::getDeliveryMethod, method.name())
                    .set(PrivacyRequest::getDeliveryEvidenceRef, evidenceRef));
            boolean deleteFile = PACKAGE_APPROVED.equals(approved.getStatus());
            if (deleteFile && markPurged(context.tenantId(), approved, PACKAGE_APPROVED, deliveredAt) != 1) {
                throw conflict();
            }
            audit.transition(context.actor().username(), PrivacyDsarAudit.DELIVERY_RECORDED, locked.getRequestNo(),
                    PrivacyCaseState.APPROVED.name(), PrivacyCaseState.CONFIRMED.name());
            if (deleteFile) {
                // 実体の削除は最後に行う（消せなければ交付記録ごと巻き戻し、PURGED の行が実体を残さない）
                deleteFile(approved.getStoredName());
            }
            return new PrivacyDsarViews.Transition(requestId, locked.getRequestNo(), PrivacyCaseState.CONFIRMED.name(),
                    version + 1);
        });
    }

    // ---------------------------------------------------------------- 削除（scheduler）

    /**
     * {@code purge_after} を過ぎた package と、終了状態の case の package を PURGED にして実体を消す（全 tenant。
     * 1回 {@value #PURGE_BATCH} 件まで）。1件ずつ別の transaction で行い、失敗した package は次の回に回す。削除した件数を返す。
     */
    public int purgeDue(LocalDateTime now) {
        LocalDateTime at = now == null ? now() : now;
        List<PrivacyExportPackage> due = packageMapper.selectPurgeDue(at, PURGE_BATCH);
        int purged = 0;
        int failed = 0;
        for (PrivacyExportPackage target : due == null ? List.<PrivacyExportPackage>of() : due) {
            if (target == null || !StringUtils.hasText(target.getTenantId())) {
                continue;
            }
            try {
                Boolean done = AccountingTenantContextHolder.runWithTenant(target.getTenantId(),
                        PrivacyDecisionBinding.BUSINESS_ZONE, () -> purgeOne(target, at));
                if (Boolean.TRUE.equals(done)) {
                    purged++;
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("{}: DSARの開示packageを削除できません。次の回に再試行します: exceptionClass={}",
                        LOG_CODE_PURGE_FAILED, e.getClass().getSimpleName());
            }
        }
        if (purged > 0 || failed > 0) {
            log.info("[privacy-dsar] 開示packageを削除しました: purged={} failed={}", purged, failed);
        }
        return purged;
    }

    private Boolean purgeOne(PrivacyExportPackage target, LocalDateTime now) {
        return execute(() -> {
            if (markPurged(target.getTenantId(), target, target.getStatus(), now.truncatedTo(ChronoUnit.MICROS)) != 1) {
                return false;
            }
            PrivacyRequest request = find(target.getTenantId(), target.getRequestId());
            audit.event(PURGE_USERNAME, PrivacyDsarAudit.PACKAGE_PURGED,
                    request == null ? "unknown" : request.getRequestNo());
            deleteFile(target.getStoredName());
            return true;
        });
    }

    // ---------------------------------------------------------------- 前提の確認

    /** 草案を作れる状態か（開示請求で、SCOPED か、期限切れの package しか無い PREVIEWED・APPROVED）。現状態を返す。 */
    private PrivacyCaseState requirePreviewable(PrivacyRequest request, long version) {
        PrivacyCaseState state = PrivacyDsarVocabulary.state(request.getStatus())
                .filter(PREVIEWABLE::contains).orElseThrow(PrivacyExportPackageService::conflict);
        requireVersionOf(request, version);
        if (!PrivacyDsarVocabulary.RequestType.ACCESS.name().equals(request.getRequestType())) {
            throw BusinessException.of(409, ERROR_EXPORT_ACCESS_ONLY);
        }
        if (state != PrivacyCaseState.SCOPED) {
            LocalDateTime now = now();
            boolean live = livePackages(request.getTenantId(), request.getId()).stream()
                    .anyMatch(candidate -> !expired(candidate, now));
            if (live) {
                throw conflict();
            }
        }
        return state;
    }

    private PrivacyCaseState requireApprovable(PrivacyRequest request, long version, Long approver) {
        PrivacyCaseState state = PrivacyDsarVocabulary.state(request.getStatus())
                .filter(APPROVABLE::contains).orElseThrow(PrivacyExportPackageService::conflict);
        requireVersionOf(request, version);
        if (request.getPreparedBy() == null) {
            throw conflict();
        }
        if (request.getPreparedBy().equals(approver)) {
            throw BusinessException.of(403, ERROR_APPROVER_IS_PREPARER);
        }
        return state;
    }

    /** 承認の対象の DRAFT package（id・内容 hash・作成者≠承認者・期限内）。 */
    private PrivacyExportPackage requireDraft(String tenantId, long requestId, long packageId, String sha, Long approver,
                                              LocalDateTime now) {
        PrivacyExportPackage target = findPackage(tenantId, requestId, packageId);
        if (target == null || !PACKAGE_DRAFT.equals(target.getStatus()) || !sha.equals(target.getContentSha256())) {
            throw BusinessException.of(409, ERROR_PACKAGE_MISMATCH);
        }
        if (approver.equals(target.getCreatedBy())) {
            throw BusinessException.of(403, ERROR_APPROVER_IS_PREPARER);
        }
        if (expired(target, now)) {
            throw BusinessException.of(409, ERROR_PACKAGE_EXPIRED);
        }
        return target;
    }

    /**
     * 承認した package（case が APPROVED、要求の id が {@code approved_package_id}、package と request の内容 hash が一致）。
     * {@code allowPurged} は交付記録だけが使う（期限で消えた後でも、承認した package の hash の照合で交付を記録できる）。
     */
    private PrivacyExportPackage requireApprovedPackage(String tenantId, PrivacyRequest request, long packageId,
                                                        boolean allowPurged) {
        if (!PrivacyCaseState.APPROVED.name().equals(request.getStatus()) || request.getApprovedPackageId() == null
                || request.getApprovedPackageId() != packageId || request.getApprovedContentSha256() == null) {
            throw BusinessException.of(409, ERROR_PACKAGE_NOT_APPROVED);
        }
        PrivacyExportPackage approved = findPackage(tenantId, request.getId(), packageId);
        boolean statusOk = approved != null && (PACKAGE_APPROVED.equals(approved.getStatus())
                || (allowPurged && PACKAGE_PURGED.equals(approved.getStatus()) && approved.getApprovedBy() != null));
        if (!statusOk || !request.getApprovedContentSha256().equals(approved.getContentSha256())) {
            throw BusinessException.of(409, ERROR_PACKAGE_NOT_APPROVED);
        }
        if (!allowPurged && expired(approved, now())) {
            throw BusinessException.of(409, ERROR_PACKAGE_EXPIRED);
        }
        return approved;
    }

    private PrivacyExportPackage requireDeliverable(String tenantId, PrivacyRequest request, long version,
                                                    long packageId, String sha) {
        if (!PrivacyCaseState.APPROVED.name().equals(request.getStatus())) {
            throw conflict();
        }
        requireVersionOf(request, version);
        PrivacyExportPackage approved = requireApprovedPackage(tenantId, request, packageId, true);
        if (!sha.equals(request.getApprovedContentSha256())) {
            throw BusinessException.of(409, ERROR_PACKAGE_NOT_APPROVED);
        }
        return approved;
    }

    /** SELECTED の本人（ちょうど1件）。 */
    private PrivacySubjectRef selectedSubject(String tenantId, long requestId) {
        List<PrivacyRequestSubjectLink> selected = linkMapper.selectList(new LambdaQueryWrapper<PrivacyRequestSubjectLink>()
                .eq(PrivacyRequestSubjectLink::getTenantId, tenantId)
                .eq(PrivacyRequestSubjectLink::getRequestId, requestId)
                .eq(PrivacyRequestSubjectLink::getLinkState, PrivacyDsarVocabulary.LinkState.SELECTED.name()));
        if (selected.size() != 1) {
            throw BusinessException.of(409, PrivacyRequestService.ERROR_SUBJECT_NOT_UNIQUE);
        }
        return PrivacySubjectRef.parse(selected.get(0).getSubjectRef()).orElseThrow(PrivacyExportPackageService::conflict);
    }

    /**
     * 範囲 snapshot を DB の値（tenant・request・SELECTED の subject・{@code identity_rule_ref}・範囲確定の行の as-of）と現在の
     * provider 登録から再計算し、request と範囲確定の行の hash に一致するか確かめる（A2a handover 2）。一致しなければ provider の
     * 登録が範囲確定の後に変わったため 409 {@code error.privacy.dsarScopeChanged}。範囲確定の行を返す。
     */
    private PrivacyAction verifyScopeSnapshot(String tenantId, PrivacyRequest request, PrivacySubjectRef subject) {
        List<PrivacyAction> rows = actionMapper.selectList(new LambdaQueryWrapper<PrivacyAction>()
                .eq(PrivacyAction::getTenantId, tenantId)
                .eq(PrivacyAction::getRequestId, request.getId())
                .eq(PrivacyAction::getActionType, PrivacyRequestService.ACTION_TYPE_MANUAL)
                .likeRight(PrivacyAction::getIdempotencyKey, PrivacyRequestService.SCOPE_KEY_PREFIX + request.getId() + ":")
                .orderByAsc(PrivacyAction::getId));
        if (rows.isEmpty() || request.getScopeSnapshotHash() == null) {
            throw BusinessException.of(409, ERROR_SCOPE_CHANGED);
        }
        PrivacyAction scope = rows.get(rows.size() - 1);
        String recomputed = PrivacyDsarScopeSnapshot.of(tenantId, request.getId(), subject.ref(),
                request.getIdentityRuleRef(), scope.getCreatedAt(), registry.providers()).hash();
        if (!recomputed.equals(request.getScopeSnapshotHash()) || !recomputed.equals(scope.getResultHash())) {
            throw BusinessException.of(409, ERROR_SCOPE_CHANGED);
        }
        return scope;
    }

    // ---------------------------------------------------------------- 書込み

    /** 検索の行（provider ごと）・要手動確認の行・草案の行を書く。冪等 key は「接頭辞:request:新しい版:連番」。 */
    private void writeSearchRows(String tenantId, long requestId, long newVersion,
                                 PrivacySearchService.SearchResult search, PrivacyExportPackageContent.Built built,
                                 LocalDateTime now) {
        LocalDateTime createdAt = now.truncatedTo(ChronoUnit.MICROS);
        String prefix = SEARCH_KEY_PREFIX + requestId + ":" + newVersion + ":";
        int sequence = 0;
        for (PrivacySearchService.ProviderHit hit : search.hits()) {
            PrivacyAction row = action(tenantId, requestId, ACTION_TYPE_SEARCH, prefix + (++sequence),
                    ACTION_STATUS_SUCCEEDED, createdAt);
            row.setProviderId(hit.providerId());
            row.setResultCount(hit.records().size());
            row.setResultHash(PrivacyExportPackageContent.sha256(recordIndex(hit)));
            insertAction(row);
        }
        for (PrivacyExportPackageContent.ManualItem item : built.manualReview()) {
            PrivacyAction row = action(tenantId, requestId, ACTION_TYPE_SEARCH, prefix + (++sequence),
                    ACTION_STATUS_MANUAL, createdAt);
            row.setProviderId(item.providerId());
            row.setDataElementId(item.dataElementId());
            row.setBlockedReason(item.reason());
            row.setResultCount(item.recordCount());
            insertAction(row);
        }
        PrivacyAction export = action(tenantId, requestId, ACTION_TYPE_EXPORT,
                EXPORT_KEY_PREFIX + requestId + ":" + newVersion, ACTION_STATUS_SUCCEEDED, createdAt);
        export.setResultCount(built.autoReleasedFields());
        export.setResultHash(built.sha256());
        export.setEvidenceRef(built.redactionReason());
        insertAction(export);
    }

    /** provider の行の索引（表・行 ID・data element。値は含めない）。検索の行の hash に使う。 */
    private static String recordIndex(PrivacySearchService.ProviderHit hit) {
        TreeSet<String> lines = new TreeSet<>();
        for (PrivacyRecord record : hit.records()) {
            lines.add(record.table() + ":" + record.rowId() + ":" + String.join(",", new TreeSet<>(record.dataElementIds())));
        }
        return hit.providerId() + "\n" + String.join("\n", lines);
    }

    /** 期限切れの package を PURGED にして実体を消す（草案の作り直しの前）。期限内の package が残っていれば 409。 */
    private void purgeExpiredOf(String tenantId, PrivacyRequest request, LocalDateTime now, String username) {
        for (PrivacyExportPackage candidate : livePackages(tenantId, request.getId())) {
            if (!expired(candidate, now)) {
                throw conflict();
            }
            if (markPurged(tenantId, candidate, candidate.getStatus(), now.truncatedTo(ChronoUnit.MICROS)) != 1) {
                throw conflict();
            }
            audit.event(username, PrivacyDsarAudit.PACKAGE_PURGED, request.getRequestNo());
            deleteFile(candidate.getStoredName());
        }
    }

    private int markPurged(String tenantId, PrivacyExportPackage target, String fromStatus, LocalDateTime purgedAt) {
        return packageMapper.update(null, new LambdaUpdateWrapper<PrivacyExportPackage>()
                .eq(PrivacyExportPackage::getTenantId, tenantId)
                .eq(PrivacyExportPackage::getId, target.getId())
                .eq(PrivacyExportPackage::getStatus, fromStatus)
                .ne(PrivacyExportPackage::getStatus, PACKAGE_PURGED)
                .set(PrivacyExportPackage::getStatus, PACKAGE_PURGED)
                .set(PrivacyExportPackage::getPurgedAt, purgedAt));
    }

    /** package を {@link FileKind#PRIVACY_EXPORT} として保存する（quarantine → scan → published）。保存名を返す。 */
    private String store(PrivacyExportPackageContent.Built built) {
        StoredFile stored;
        try {
            stored = fileStorage.store(built.bytes(), PACKAGE_FILE_NAME, FileKind.PRIVACY_EXPORT);
        } catch (RuntimeException e) {
            log.warn("[privacy-dsar] 開示packageを保存できません: exceptionClass={} messageKey={}",
                    e.getClass().getSimpleName(), e instanceof BusinessException be ? be.getMessageKey() : null);
            throw BusinessException.of(503, ERROR_PACKAGE_FILE_UNAVAILABLE);
        }
        if (stored == null || !StringUtils.hasText(stored.getStoredName())) {
            throw BusinessException.of(503, ERROR_PACKAGE_FILE_UNAVAILABLE);
        }
        return stored.getStoredName();
    }

    /** 保存実体を読み、内容 hash と大きさが package 行と一致することを確かめて返す。 */
    private byte[] readVerified(PrivacyExportPackage target) {
        byte[] content;
        try {
            Resource resource = fileStorage.load(target.getStoredName());
            try (InputStream in = resource.getInputStream()) {
                content = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8L, FileKind.PRIVACY_EXPORT.getMaxBytes() + 1));
            }
        } catch (BusinessException e) {
            log.warn("[privacy-dsar] 開示packageの実体を開けません: messageKey={}", e.getMessageKey());
            throw BusinessException.of(e.getCode() == 403 ? 403 : 503, e.getCode() == 403
                    ? e.getMessageKey() : ERROR_PACKAGE_FILE_UNAVAILABLE);
        } catch (IOException | RuntimeException e) {
            log.warn("[privacy-dsar] 開示packageの実体を読めません: exceptionClass={}", e.getClass().getSimpleName());
            throw BusinessException.of(503, ERROR_PACKAGE_FILE_UNAVAILABLE);
        }
        if (target.getSizeBytes() == null || content.length != target.getSizeBytes()
                || !PrivacyExportPackageContent.sha256(content).equals(target.getContentSha256())) {
            throw BusinessException.of(409, ERROR_PACKAGE_INTEGRITY);
        }
        return content;
    }

    private void deleteFile(String storedName) {
        try {
            fileStorage.delete(storedName);
        } catch (RuntimeException e) {
            log.warn("[privacy-dsar] 開示packageの実体を消せません: exceptionClass={}", e.getClass().getSimpleName());
            throw BusinessException.of(503, ERROR_PACKAGE_FILE_UNAVAILABLE);
        }
    }

    private void deleteQuietly(String storedName) {
        try {
            fileStorage.delete(storedName);
        } catch (RuntimeException e) {
            // 行を書けなかった package の実体。metadata が残るため孤児清掃では消えない。監視の code で拾う
            log.warn("{}: 記録できなかった開示packageの実体を消せません: exceptionClass={}", LOG_CODE_FILE_CLEANUP_FAILED,
                    e.getClass().getSimpleName());
        }
    }

    // ---------------------------------------------------------------- 内部

    private List<String> actions(PrivacyCaseState state, boolean access, PrivacyRequest request,
                                 PrivacyExportPackage current, PrivacyDsarViews.PackageSummary summary, Long me) {
        List<String> actions = new ArrayList<>();
        if (state == null || !access) {
            return actions;
        }
        boolean live = current != null && !PACKAGE_PURGED.equals(current.getStatus()) && !summary.expired();
        switch (state) {
            case SCOPED -> actions.add(PrivacyDsarVocabulary.NextAction.PREVIEW.name());
            case PREVIEWED, APPROVAL_PENDING -> {
                if (!live) {
                    actions.add(PrivacyDsarVocabulary.NextAction.PREVIEW.name());
                } else if (PACKAGE_DRAFT.equals(current.getStatus()) && me != null && !me.equals(current.getCreatedBy())
                        && !me.equals(request.getPreparedBy())) {
                    actions.add(PrivacyDsarVocabulary.NextAction.APPROVE.name());
                }
            }
            case APPROVED -> {
                if (!live) {
                    actions.add(PrivacyDsarVocabulary.NextAction.PREVIEW.name());
                } else if (PACKAGE_APPROVED.equals(current.getStatus())
                        && current.getId().equals(request.getApprovedPackageId())) {
                    actions.add(PrivacyDsarVocabulary.NextAction.DOWNLOAD.name());
                    actions.add(PrivacyDsarVocabulary.NextAction.RECORD_DELIVERY.name());
                }
                if (!live && request.getApprovedPackageId() != null) {
                    // 期限で消えた後でも、承認した package の hash の照合で交付を記録できる
                    actions.add(PrivacyDsarVocabulary.NextAction.RECORD_DELIVERY.name());
                }
            }
            default -> {
            }
        }
        return actions;
    }

    /** SELECTED の本人の保全 hold の状態（読取りの表示だけ。hold の判定・破壊的経路には関与しない）。 */
    private PrivacyDsarViews.HoldStatus holdStatus(String tenantId, long requestId) {
        List<PrivacyRequestSubjectLink> selected = linkMapper.selectList(new LambdaQueryWrapper<PrivacyRequestSubjectLink>()
                .eq(PrivacyRequestSubjectLink::getTenantId, tenantId)
                .eq(PrivacyRequestSubjectLink::getRequestId, requestId)
                .eq(PrivacyRequestSubjectLink::getLinkState, PrivacyDsarVocabulary.LinkState.SELECTED.name()));
        if (selected.size() != 1) {
            return new PrivacyDsarViews.HoldStatus(HOLD_NOT_SCOPED);
        }
        String subjectRef = selected.get(0).getSubjectRef();
        try {
            PrivacyHoldGate gate = holdGate.get();
            if (gate == null) {
                return new PrivacyDsarViews.HoldStatus(HOLD_UNKNOWN);
            }
            PrivacyHoldGate.Decision decision = gate.inspect(tenantId);
            return new PrivacyDsarViews.HoldStatus(switch (decision.state()) {
                case NO_HOLDS -> HOLD_NONE;
                case UNAVAILABLE -> HOLD_UNKNOWN;
                case VERIFIED -> decision.heldSubjects().contains(subjectRef) ? HOLD_HELD : HOLD_NOT_HELD;
            });
        } catch (RuntimeException e) {
            log.warn("[privacy-dsar] holdの状態を表示のために確認できません: exceptionClass={}", e.getClass().getSimpleName());
            return new PrivacyDsarViews.HoldStatus(HOLD_UNKNOWN);
        }
    }

    private PrivacyExportPackage latestPackage(String tenantId, long requestId) {
        List<PrivacyExportPackage> rows = packageMapper.selectList(new LambdaQueryWrapper<PrivacyExportPackage>()
                .eq(PrivacyExportPackage::getTenantId, tenantId)
                .eq(PrivacyExportPackage::getRequestId, requestId)
                .orderByDesc(PrivacyExportPackage::getId));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<PrivacyExportPackage> livePackages(String tenantId, long requestId) {
        return packageMapper.selectList(new LambdaQueryWrapper<PrivacyExportPackage>()
                .eq(PrivacyExportPackage::getTenantId, tenantId)
                .eq(PrivacyExportPackage::getRequestId, requestId)
                .ne(PrivacyExportPackage::getStatus, PACKAGE_PURGED)
                .orderByAsc(PrivacyExportPackage::getId));
    }

    private PrivacyExportPackage findPackage(String tenantId, long requestId, long packageId) {
        return packageMapper.selectOne(new LambdaQueryWrapper<PrivacyExportPackage>()
                .eq(PrivacyExportPackage::getTenantId, tenantId)
                .eq(PrivacyExportPackage::getRequestId, requestId)
                .eq(PrivacyExportPackage::getId, packageId));
    }

    private PrivacyAction latestExportRow(String tenantId, long requestId) {
        List<PrivacyAction> rows = actionMapper.selectList(new LambdaQueryWrapper<PrivacyAction>()
                .eq(PrivacyAction::getTenantId, tenantId)
                .eq(PrivacyAction::getRequestId, requestId)
                .eq(PrivacyAction::getActionType, ACTION_TYPE_EXPORT)
                .likeRight(PrivacyAction::getIdempotencyKey, EXPORT_KEY_PREFIX + requestId + ":")
                .orderByDesc(PrivacyAction::getId));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<PrivacyAction> searchRows(String tenantId, long requestId, String version) {
        if (version == null) {
            return List.of();
        }
        return actionMapper.selectList(new LambdaQueryWrapper<PrivacyAction>()
                .eq(PrivacyAction::getTenantId, tenantId)
                .eq(PrivacyAction::getRequestId, requestId)
                .eq(PrivacyAction::getActionType, ACTION_TYPE_SEARCH)
                .likeRight(PrivacyAction::getIdempotencyKey, SEARCH_KEY_PREFIX + requestId + ":" + version + ":")
                .orderByAsc(PrivacyAction::getId));
    }

    /** 草案の行の冪等 key（{@code DSAR-EXPORT:<request>:<版>}）から版を取り出す。 */
    private static String versionOf(PrivacyAction export) {
        String key = export.getIdempotencyKey();
        int separator = key == null ? -1 : key.lastIndexOf(':');
        return separator < 0 ? null : key.substring(separator + 1);
    }

    private static PrivacyAction action(String tenantId, long requestId, String type, String key, String status,
                                        LocalDateTime createdAt) {
        PrivacyAction action = new PrivacyAction();
        action.setTenantId(tenantId);
        action.setRequestId(requestId);
        action.setActionType(type);
        action.setStatus(status);
        action.setIdempotencyKey(key);
        action.setRowVersion(0L);
        action.setCreatedAt(createdAt);
        return action;
    }

    private void insertAction(PrivacyAction action) {
        try {
            actionMapper.insert(action);
        } catch (DuplicateKeyException e) {
            throw conflict();
        }
    }

    private Context begin(String action) {
        accessGuard.requireAllowed(PrivacyAccessGuard.Capability.DSAR, action);
        PrivacyRequestService.Actor actor = actorSource.get();
        return new Context(actor, requireCallerTenant(actor));
    }

    private Context beginManage(String action) {
        Context context = begin(action);
        if (context.actor().userId() == null || context.actor().userId() <= 0) {
            throw BusinessException.of(403, "error.accessDenied");
        }
        return context;
    }

    private PrivacyDsarDecisions.Readiness requireDecisions() {
        PrivacyDsarDecisions.Readiness readiness = decisions.evaluate();
        if (!readiness.ready()) {
            throw BusinessException.of(409, PrivacyRequestService.ERROR_DECISION_UNKNOWN);
        }
        return readiness;
    }

    private <T> T execute(Supplier<T> work) {
        return transactions.execute(status -> work.get());
    }

    /** request 行の CAS（現状態と版が一致する行だけを更新し、版を1つ進める。0件なら 409）。 */
    private void cas(String tenantId, long requestId, PrivacyCaseState from, long version, LocalDateTime now,
                     Consumer<LambdaUpdateWrapper<PrivacyRequest>> changes) {
        LambdaUpdateWrapper<PrivacyRequest> update = new LambdaUpdateWrapper<PrivacyRequest>()
                .eq(PrivacyRequest::getTenantId, tenantId)
                .eq(PrivacyRequest::getId, requestId)
                .eq(PrivacyRequest::getStatus, from.name())
                .eq(PrivacyRequest::getRowVersion, version)
                .setSql("row_version = row_version + 1")
                .set(PrivacyRequest::getUpdatedAt, now);
        changes.accept(update);
        if (requestMapper.update(null, update) != 1) {
            throw conflict();
        }
    }

    private PrivacyRequest load(String tenantId, long requestId) {
        PrivacyRequest request = find(tenantId, requestId);
        if (request == null) {
            throw BusinessException.of(404, PrivacyRequestService.ERROR_NOT_FOUND);
        }
        return request;
    }

    private PrivacyRequest find(String tenantId, long requestId) {
        if (requestId <= 0) {
            return null;
        }
        return requestMapper.selectOne(new LambdaQueryWrapper<PrivacyRequest>()
                .eq(PrivacyRequest::getTenantId, tenantId)
                .eq(PrivacyRequest::getId, requestId));
    }

    private String requireCallerTenant(PrivacyRequestService.Actor actor) {
        String callerTenant = actor == null ? null : actor.tenantId();
        String authorizationTenant = oidcProperties != null && StringUtils.hasText(oidcProperties.getTenantId())
                ? oidcProperties.getTenantId() : "default";
        if (!StringUtils.hasText(callerTenant) || !authorizationTenant.equals(callerTenant)) {
            throw BusinessException.of(403, "error.accessDenied");
        }
        return callerTenant;
    }

    private static void requireVersionOf(PrivacyRequest request, long version) {
        if (request.getRowVersion() == null || request.getRowVersion() != version) {
            throw conflict();
        }
    }

    private static boolean expired(PrivacyExportPackage target, LocalDateTime now) {
        return target.getPurgeAfter() == null || !target.getPurgeAfter().isAfter(now);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock.withZone(PrivacyDecisionBinding.BUSINESS_ZONE));
    }

    private static long requireVersion(Long version) {
        if (version == null || version < 0) {
            throw invalid();
        }
        return version;
    }

    private static long requirePositive(Long id) {
        if (id == null || id <= 0) {
            throw invalid();
        }
        return id;
    }

    private static String requireSha(String value) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw invalid();
        }
        return value;
    }

    private static String requireReference(String raw) {
        if (raw == null || !PrivacyDsarVocabulary.REFERENCE.matcher(raw).matches()) {
            throw invalid();
        }
        if (PrivacyDsarVocabulary.looksLikeDocumentNumber(raw)) {
            throw BusinessException.of(400, PrivacyRequestService.ERROR_REFERENCE_NUMBER);
        }
        return raw;
    }

    private static long version(PrivacyRequest request) {
        return request.getRowVersion() == null ? 0L : request.getRowVersion();
    }

    private static String display(LocalDateTime value) {
        return value == null ? null : DISPLAY_TIME.format(value);
    }

    private static BusinessException invalid() {
        return BusinessException.of(400, PrivacyRequestService.ERROR_INVALID);
    }

    private static BusinessException conflict() {
        return BusinessException.of(409, PrivacyRequestService.ERROR_CONFLICT);
    }

    private record Context(PrivacyRequestService.Actor actor, String tenantId) {
    }
}
