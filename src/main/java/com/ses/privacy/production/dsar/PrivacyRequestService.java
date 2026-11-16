package com.ses.privacy.production.dsar;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.PageUtils;
import com.ses.common.util.SecurityUtils;
import com.ses.config.OidcSecurityProperties;
import com.ses.entity.privacy.PrivacyAction;
import com.ses.entity.privacy.PrivacyRequest;
import com.ses.entity.privacy.PrivacyRequestSubjectLink;
import com.ses.mapper.privacy.PrivacyActionMapper;
import com.ses.mapper.privacy.PrivacyRequestMapper;
import com.ses.mapper.privacy.PrivacyRequestSubjectLinkMapper;
import com.ses.privacy.foundation.PrivacyCaseState;
import com.ses.privacy.production.PrivacyAccessGuard;
import com.ses.privacy.production.PrivacyDecisionBinding;
import com.ses.privacy.production.ProductionPrivacyProviderRegistry;
import com.ses.privacy.production.provider.PrivacyRecord;
import com.ses.privacy.production.provider.PrivacySubjectRef;
import com.ses.privacy.production.provider.ProductionPrivacyProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * NF07 DSAR の受付〜SCOPED（NF07-A2a、production-bridge-phased-plan.md §3.5・§3.5.1）。本人確認・名寄せは人が行い、
 * 本 service は記録・期限・状態遷移だけを受け持つ（処分・交付・外部送信はしない。I-4）。
 *
 * <p>状態遷移:
 * <ol>
 *   <li>受付（REQUESTED）: {@code intake_ticket_ref} 必須。D-01・D-06 が有効で期限規則が設定されていれば期限日を計算し、
 *       それ以外は NULL のまま受け付ける（受付日時は決定の有無に関係なく記録する）。</li>
 *   <li>本人確認（IDENTITY_VERIFIED）: 証跡の参照と、適用した D-06 の決定ID（設定値）を記録する。</li>
 *   <li>名寄せ: privacy-admin が subject 参照で候補を登録し（F2 provider に記録が1件以上あるものだけ）、SELECTED / REJECTED を
 *       人が裁定する。氏名の一致で自動結合する経路は持たない（同姓同名の別人は候補にもならない）。</li>
 *   <li>範囲確定（SCOPED）: SELECTED がちょうど1件のときだけ進み、範囲 snapshot の hash を request と MANUAL_ACTION 行に書く。
 *       1件でなければ 409 で状態を変えない（誤操作で終了状態へ落とさない。本人不明・複数候補で進められないと判断したら、
 *       privacy-admin が BLOCKED として記録する）。</li>
 *   <li>終了（BLOCKED / CANCELLED）: REQUESTED・IDENTITY_VERIFIED・SCOPED から、理由コード付きで記録する。</li>
 * </ol>
 * 本人確認以降（本人確認・候補の登録と裁定・範囲確定）は D-01・D-06 が欠ければ 409 {@code error.privacy.dsarDecisionUnknown}
 * （DECISION_UNKNOWN）で進めない。受付と終了は決定を前提にしない（受付日時の記録と取下げを止めない）。
 *
 * <p>同時実行: 状態を変える操作は全て、request 行を「現状態・{@code row_version}」で条件付けた CAS（{@code row_version + 1}）を
 * 先に通す。候補の登録・裁定も同じ行の CAS を通すため、範囲確定と候補の変更は直列化され、古い版の要求は 409
 * {@code error.privacy.dsarConflict} になる。監査（{@link PrivacyDsarAudit}）は同じ transaction の中で必須記録し、
 * 記録できなければ操作ごと巻き戻す。監査・log・通知の本文に subject 参照・氏名を入れない。
 */
public class PrivacyRequestService {

    private static final Logger log = LoggerFactory.getLogger(PrivacyRequestService.class);

    static final String VIEW_ACTION = "privacy.dsar.view";
    static final String MANAGE_ACTION = "privacy.dsar.manage";
    /** 1件の request に登録できる候補の上限（詳細の表示で provider を候補ごとに照会するため）。 */
    static final int MAX_CANDIDATES = 20;
    /** 受付日時として遡って記録できる日数。 */
    static final int MAX_RECEIVED_AGE_DAYS = 366;
    static final String ACTION_TYPE_MANUAL = "MANUAL_ACTION";
    static final String ACTION_STATUS_SUCCEEDED = "SUCCEEDED";
    static final String ACTION_STATUS_BLOCKED = "BLOCKED";
    /** 範囲確定の MANUAL_ACTION 行の印（{@code evidence_ref}）。 */
    static final String SCOPE_EVIDENCE = "SCOPE_SNAPSHOT";
    static final String DEFAULT_SELECTED_CODE = "MANUAL_SELECTED";
    static final String DEFAULT_REJECTED_CODE = "MANUAL_REJECTED";

    static final String ERROR_INVALID = "error.privacy.dsarInvalidRequest";
    static final String ERROR_NOT_FOUND = "error.privacy.dsarNotFound";
    static final String ERROR_CONFLICT = "error.privacy.dsarConflict";
    static final String ERROR_DECISION_UNKNOWN = "error.privacy.dsarDecisionUnknown";
    static final String ERROR_SUBJECT_NOT_UNIQUE = "error.privacy.dsarSubjectNotUnique";
    static final String ERROR_CANDIDATE_NOT_FOUND = "error.privacy.dsarCandidateNotFound";
    static final String ERROR_CANDIDATE_DUPLICATE = "error.privacy.dsarCandidateDuplicate";
    static final String ERROR_CANDIDATE_LIMIT = "error.privacy.dsarCandidateLimit";
    static final String ERROR_REOPEN_INVALID = "error.privacy.dsarReopenInvalid";
    static final String ERROR_SOURCE_UNAVAILABLE = "error.privacy.dsarSourceUnavailable";

    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter REQUEST_NO_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    /** 受付番号の乱数部（Crockford base32。紛らわしい I・L・O・U を除く）。 */
    private static final char[] REQUEST_NO_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final int REQUEST_NO_RANDOM_LENGTH = 8;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PrivacyAccessGuard accessGuard;
    private final OidcSecurityProperties oidcProperties;
    private final PrivacyDsarDecisions decisions;
    private final PrivacyRequestMapper requestMapper;
    private final PrivacyRequestSubjectLinkMapper linkMapper;
    private final PrivacyActionMapper actionMapper;
    private final ProductionPrivacyProviderRegistry registry;
    private final PrivacyDsarAudit audit;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Supplier<Actor> actorSource;

    PrivacyRequestService(PrivacyAccessGuard accessGuard, OidcSecurityProperties oidcProperties,
                          PrivacyDsarDecisions decisions, PrivacyRequestMapper requestMapper,
                          PrivacyRequestSubjectLinkMapper linkMapper, PrivacyActionMapper actionMapper,
                          ProductionPrivacyProviderRegistry registry, PrivacyDsarAudit audit,
                          TransactionTemplate transactions, Clock clock, Supplier<Actor> actorSource) {
        this.accessGuard = Objects.requireNonNull(accessGuard, "accessGuard");
        this.oidcProperties = oidcProperties;
        this.decisions = Objects.requireNonNull(decisions, "decisions");
        this.requestMapper = Objects.requireNonNull(requestMapper, "requestMapper");
        this.linkMapper = Objects.requireNonNull(linkMapper, "linkMapper");
        this.actionMapper = Objects.requireNonNull(actionMapper, "actionMapper");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.actorSource = actorSource == null ? Actor::fromSecurityContext : actorSource;
    }

    // ---------------------------------------------------------------- 照会

    /** 画面の入口（{@code GET /privacy/dsar}・{@code GET /privacy/dsar/{id}}）の再認可。 */
    public void requirePageAccess() {
        accessGuard.requireAllowed(PrivacyAccessGuard.Capability.DSAR, VIEW_ACTION);
    }

    /** 一覧（subject 参照を含めない）と前提。 */
    public PrivacyDsarViews.Overview overview(String status, long page, long size) {
        Context context = begin(VIEW_ACTION);
        LambdaQueryWrapper<PrivacyRequest> query = new LambdaQueryWrapper<PrivacyRequest>()
                .eq(PrivacyRequest::getTenantId, context.tenantId());
        if (StringUtils.hasText(status)) {
            PrivacyCaseState filter = PrivacyDsarVocabulary.state(status).orElseThrow(PrivacyRequestService::invalid);
            query.eq(PrivacyRequest::getStatus, filter.name());
        }
        query.orderByDesc(PrivacyRequest::getReceivedAt).orderByDesc(PrivacyRequest::getId);
        Page<PrivacyRequest> result = requestMapper.selectPage(PageUtils.safePage(page, size, 20, 100), query);
        LocalDate today = PrivacyDecisionBinding.businessDate(clock);
        int warningDays = decisions.deadlineWarningDays();
        List<PrivacyDsarViews.Summary> summaries = new ArrayList<>();
        for (PrivacyRequest request : result.getRecords()) {
            boolean tracked = PrivacyDsarVocabulary.DEADLINE_TRACKED_STATES.contains(request.getStatus());
            boolean overdue = tracked && request.getDueDate() != null && request.getDueDate().isBefore(today);
            boolean dueSoon = tracked && !overdue && request.getDueDate() != null
                    && !request.getDueDate().isAfter(today.plusDays(warningDays));
            summaries.add(new PrivacyDsarViews.Summary(request.getId(), request.getRequestNo(),
                    request.getRequestType(), request.getStatus(), request.getChannel(),
                    display(request.getReceivedAt()), date(request.getDueDate()), overdue, dueSoon,
                    version(request)));
        }
        return new PrivacyDsarViews.Overview(decisions.evaluate().toView(), summaries, result.getTotal(),
                result.getCurrent(), result.getSize());
    }

    /** 詳細（候補は subject 参照と記録の件数だけ）。 */
    public PrivacyDsarViews.Detail detail(long requestId) {
        Context context = begin(VIEW_ACTION);
        PrivacyRequest request = load(context.tenantId(), requestId);
        List<PrivacyRequestSubjectLink> links = links(context.tenantId(), requestId);
        List<PrivacyDsarViews.Candidate> candidates = new ArrayList<>();
        int selected = 0;
        for (PrivacyRequestSubjectLink link : links) {
            if (PrivacyDsarVocabulary.LinkState.SELECTED.name().equals(link.getLinkState())) {
                selected++;
            }
            candidates.add(candidateView(context.tenantId(), link));
        }
        List<PrivacyAction> actions = actionMapper.selectList(new LambdaQueryWrapper<PrivacyAction>()
                .eq(PrivacyAction::getTenantId, context.tenantId())
                .eq(PrivacyAction::getRequestId, requestId)
                .eq(PrivacyAction::getActionType, ACTION_TYPE_MANUAL)
                .orderByAsc(PrivacyAction::getId));
        PrivacyDsarViews.ScopeRecord scope = null;
        PrivacyDsarViews.ClosureRecord closure = null;
        for (PrivacyAction action : actions) {
            if (SCOPE_EVIDENCE.equals(action.getEvidenceRef()) && action.getBlockedReason() == null) {
                scope = new PrivacyDsarViews.ScopeRecord(display(action.getCreatedAt()), action.getResultCount());
            } else if (action.getBlockedReason() != null) {
                closure = new PrivacyDsarViews.ClosureRecord(request.getStatus(), action.getBlockedReason(),
                        action.getEvidenceRef(), display(action.getCreatedAt()));
            }
        }
        Optional<PrivacyCaseState> state = PrivacyDsarVocabulary.state(request.getStatus());
        List<String> nextActions = PrivacyDsarVocabulary.nextActions(state.orElse(null)).stream()
                .map(Enum::name).toList();
        LocalDate today = PrivacyDecisionBinding.businessDate(clock);
        boolean overdue = PrivacyDsarVocabulary.DEADLINE_TRACKED_STATES.contains(request.getStatus())
                && request.getDueDate() != null && request.getDueDate().isBefore(today);
        return new PrivacyDsarViews.Detail(request.getId(), request.getRequestNo(), request.getRequestType(),
                request.getStatus(), request.getChannel(), display(request.getReceivedAt()),
                date(request.getDueDate()), overdue, request.getIntakeTicketRef(), request.getIdentityState(),
                request.getIdentityEvidenceRef(), request.getIdentityRuleRef(),
                display(request.getIdentityVerifiedAt()), request.getReopenOfRequestId(), version(request),
                candidates, selected, scope, closure, nextActions, decisions.evaluate().toView());
    }

    // ---------------------------------------------------------------- 状態遷移

    /** 受付（REQUESTED）。 */
    public PrivacyDsarViews.Transition intake(PrivacyDsarCommands.Intake command) {
        Context context = beginManage();
        if (command == null) {
            throw invalid();
        }
        PrivacyDsarVocabulary.RequestType type = PrivacyDsarVocabulary
                .parse(PrivacyDsarVocabulary.RequestType.class, command.requestType())
                .orElseThrow(PrivacyRequestService::invalid);
        PrivacyDsarVocabulary.Channel channel = PrivacyDsarVocabulary
                .parse(PrivacyDsarVocabulary.Channel.class, command.channel())
                .orElseThrow(PrivacyRequestService::invalid);
        String ticketRef = requireReference(command.intakeTicketRef());
        Long reopenOf = command.reopenOfRequestId();
        if (reopenOf != null && reopenOf <= 0) {
            throw invalid();
        }
        LocalDateTime now = now();
        LocalDateTime receivedAt = receivedAt(command.receivedAt(), now);
        PrivacyDsarDecisions.Readiness readiness = decisions.evaluate();
        LocalDate dueDate = readiness.dueDays() == null ? null : receivedAt.toLocalDate().plusDays(readiness.dueDays());
        return execute(() -> {
            if (reopenOf != null) {
                PrivacyRequest original = find(context.tenantId(), reopenOf);
                boolean terminal = original != null && PrivacyDsarVocabulary.state(original.getStatus())
                        .filter(PrivacyDsarVocabulary.TERMINAL_STATES::contains).isPresent();
                if (!terminal) {
                    throw BusinessException.of(409, ERROR_REOPEN_INVALID);
                }
            }
            PrivacyRequest row = new PrivacyRequest();
            row.setTenantId(context.tenantId());
            row.setRequestNo(newRequestNo(now.toLocalDate()));
            row.setRequestType(type.name());
            row.setStatus(PrivacyCaseState.REQUESTED.name());
            row.setChannel(channel.name());
            row.setReceivedAt(receivedAt);
            row.setDueDate(dueDate);
            row.setIntakeTicketRef(ticketRef);
            row.setIdentityState(PrivacyDsarVocabulary.IdentityState.UNVERIFIED.name());
            row.setCaseOwnerId(context.actor().userId());
            row.setReopenOfRequestId(reopenOf);
            row.setRowVersion(0L);
            row.setCreatedBy(context.actor().userId());
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            try {
                requestMapper.insert(row);
            } catch (DuplicateKeyException e) {
                throw conflict();
            }
            audit.transition(context.actor().username(), PrivacyDsarAudit.INTAKE_RECORDED, row.getRequestNo(), null,
                    PrivacyCaseState.REQUESTED.name());
            return new PrivacyDsarViews.Transition(row.getId(), row.getRequestNo(), row.getStatus(), 0L);
        });
    }

    /** 本人確認の記録（REQUESTED → IDENTITY_VERIFIED）。 */
    public PrivacyDsarViews.Transition verifyIdentity(long requestId, PrivacyDsarCommands.IdentityVerification command) {
        Context context = beginManage();
        PrivacyDsarDecisions.Readiness readiness = requireDecisions();
        long version = requireVersion(command == null ? null : command.rowVersion());
        String evidenceRef = requireReference(command.identityEvidenceRef());
        return execute(() -> {
            PrivacyRequest request = load(context.tenantId(), requestId);
            requireState(request, PrivacyCaseState.REQUESTED, version);
            LocalDateTime now = now();
            cas(context.tenantId(), requestId, PrivacyCaseState.REQUESTED, version, now, update -> update
                    .set(PrivacyRequest::getStatus, PrivacyCaseState.IDENTITY_VERIFIED.name())
                    .set(PrivacyRequest::getIdentityState, PrivacyDsarVocabulary.IdentityState.VERIFIED.name())
                    .set(PrivacyRequest::getIdentityEvidenceRef, evidenceRef)
                    .set(PrivacyRequest::getIdentityRuleRef, readiness.d06DecisionId())
                    .set(PrivacyRequest::getIdentityVerifiedBy, context.actor().userId())
                    .set(PrivacyRequest::getIdentityVerifiedAt, now));
            audit.transition(context.actor().username(), PrivacyDsarAudit.IDENTITY_VERIFIED, request.getRequestNo(),
                    PrivacyCaseState.REQUESTED.name(), PrivacyCaseState.IDENTITY_VERIFIED.name());
            return new PrivacyDsarViews.Transition(requestId, request.getRequestNo(),
                    PrivacyCaseState.IDENTITY_VERIFIED.name(), version + 1);
        });
    }

    /** 名寄せの候補を登録する（F2 provider に記録がある subject だけ）。 */
    public PrivacyDsarViews.Transition addCandidate(long requestId, PrivacyDsarCommands.CandidateAddition command) {
        Context context = beginManage();
        requireDecisions();
        long version = requireVersion(command == null ? null : command.rowVersion());
        PrivacySubjectRef subject = PrivacySubjectRef.parse(command.subjectRef())
                .orElseThrow(PrivacyRequestService::invalid);
        // provider の照会は read-only で、transaction の外で行う（記録が無い subject は候補にしない）
        Footprint footprint = footprint(context.tenantId(), subject);
        if (footprint.recordCount() == 0) {
            throw BusinessException.of(400, ERROR_CANDIDATE_NOT_FOUND);
        }
        return execute(() -> {
            PrivacyRequest request = load(context.tenantId(), requestId);
            requireState(request, PrivacyCaseState.IDENTITY_VERIFIED, version);
            List<PrivacyRequestSubjectLink> existing = links(context.tenantId(), requestId);
            if (existing.stream().anyMatch(link -> subject.ref().equals(link.getSubjectRef()))) {
                throw BusinessException.of(409, ERROR_CANDIDATE_DUPLICATE);
            }
            if (existing.size() >= MAX_CANDIDATES) {
                throw BusinessException.of(409, ERROR_CANDIDATE_LIMIT);
            }
            cas(context.tenantId(), requestId, PrivacyCaseState.IDENTITY_VERIFIED, version, now(), update -> { });
            PrivacyRequestSubjectLink link = new PrivacyRequestSubjectLink();
            link.setTenantId(context.tenantId());
            link.setRequestId(requestId);
            link.setSubjectRef(subject.ref());
            link.setLinkState(PrivacyDsarVocabulary.LinkState.CANDIDATE.name());
            try {
                linkMapper.insert(link);
            } catch (DuplicateKeyException e) {
                throw BusinessException.of(409, ERROR_CANDIDATE_DUPLICATE);
            }
            audit.linkTransition(context.actor().username(), PrivacyDsarAudit.CANDIDATE_ADDED, request.getRequestNo(),
                    null, PrivacyDsarVocabulary.LinkState.CANDIDATE.name());
            return new PrivacyDsarViews.Transition(requestId, request.getRequestNo(),
                    PrivacyCaseState.IDENTITY_VERIFIED.name(), version + 1);
        });
    }

    /** 候補を人が裁定する（SELECTED / REJECTED）。他の候補の状態は変えない。 */
    public PrivacyDsarViews.Transition resolveCandidate(long requestId, long linkId,
                                                        PrivacyDsarCommands.CandidateResolution command) {
        Context context = beginManage();
        requireDecisions();
        long version = requireVersion(command == null ? null : command.rowVersion());
        PrivacyDsarVocabulary.LinkState target = PrivacyDsarVocabulary
                .parse(PrivacyDsarVocabulary.LinkState.class, command.linkState())
                .filter(state -> state != PrivacyDsarVocabulary.LinkState.CANDIDATE)
                .orElseThrow(PrivacyRequestService::invalid);
        String code = resolutionCode(command.resolutionCode(), target);
        return execute(() -> {
            PrivacyRequest request = load(context.tenantId(), requestId);
            requireState(request, PrivacyCaseState.IDENTITY_VERIFIED, version);
            PrivacyRequestSubjectLink link = linkId <= 0 ? null : linkMapper.selectOne(
                    new LambdaQueryWrapper<PrivacyRequestSubjectLink>()
                            .eq(PrivacyRequestSubjectLink::getTenantId, context.tenantId())
                            .eq(PrivacyRequestSubjectLink::getRequestId, requestId)
                            .eq(PrivacyRequestSubjectLink::getId, linkId));
            if (link == null) {
                throw BusinessException.of(404, ERROR_NOT_FOUND);
            }
            LocalDateTime now = now();
            cas(context.tenantId(), requestId, PrivacyCaseState.IDENTITY_VERIFIED, version, now, update -> { });
            int updated = linkMapper.update(null, new LambdaUpdateWrapper<PrivacyRequestSubjectLink>()
                    .eq(PrivacyRequestSubjectLink::getTenantId, context.tenantId())
                    .eq(PrivacyRequestSubjectLink::getRequestId, requestId)
                    .eq(PrivacyRequestSubjectLink::getId, linkId)
                    .set(PrivacyRequestSubjectLink::getLinkState, target.name())
                    .set(PrivacyRequestSubjectLink::getResolvedBy, context.actor().userId())
                    .set(PrivacyRequestSubjectLink::getResolvedAt, now)
                    .set(PrivacyRequestSubjectLink::getResolutionCode, code));
            if (updated != 1) {
                throw conflict();
            }
            audit.linkTransition(context.actor().username(), PrivacyDsarAudit.CANDIDATE_RESOLVED,
                    request.getRequestNo(), link.getLinkState(), target.name());
            return new PrivacyDsarViews.Transition(requestId, request.getRequestNo(),
                    PrivacyCaseState.IDENTITY_VERIFIED.name(), version + 1);
        });
    }

    /** 範囲を確定する（IDENTITY_VERIFIED → SCOPED。SELECTED がちょうど1件のときだけ）。 */
    public PrivacyDsarViews.Transition scope(long requestId, PrivacyDsarCommands.ScopeConfirmation command) {
        Context context = beginManage();
        PrivacyDsarDecisions.Readiness readiness = requireDecisions();
        long version = requireVersion(command == null ? null : command.rowVersion());
        List<ProductionPrivacyProvider> providers = registry.providers();
        if (providers.isEmpty()) {
            throw BusinessException.of(503, ERROR_SOURCE_UNAVAILABLE);
        }
        return execute(() -> {
            PrivacyRequest request = load(context.tenantId(), requestId);
            requireState(request, PrivacyCaseState.IDENTITY_VERIFIED, version);
            List<PrivacyRequestSubjectLink> selected = selectedLinks(context.tenantId(), requestId);
            if (selected.size() != 1) {
                throw BusinessException.of(409, ERROR_SUBJECT_NOT_UNIQUE);
            }
            LocalDateTime asOf = now().truncatedTo(ChronoUnit.MICROS);
            PrivacyDsarScopeSnapshot.Snapshot snapshot = PrivacyDsarScopeSnapshot.of(context.tenantId(), requestId,
                    selected.get(0).getSubjectRef(), readiness.d06DecisionId(), asOf, providers);
            cas(context.tenantId(), requestId, PrivacyCaseState.IDENTITY_VERIFIED, version, asOf, update -> update
                    .set(PrivacyRequest::getStatus, PrivacyCaseState.SCOPED.name())
                    .set(PrivacyRequest::getScopeSnapshotHash, snapshot.hash()));
            // 候補の変更は全て同じ行の CAS を通るため件数はここで変わらないが、変わっていれば確定ごと巻き戻す
            if (selectedLinks(context.tenantId(), requestId).size() != 1) {
                throw BusinessException.of(409, ERROR_SUBJECT_NOT_UNIQUE);
            }
            PrivacyAction action = manualAction(context.tenantId(), requestId, "DSAR-SCOPE:" + requestId + ":"
                    + (version + 1), ACTION_STATUS_SUCCEEDED, null, SCOPE_EVIDENCE, asOf);
            action.setResultCount(snapshot.elementCount());
            action.setResultHash(snapshot.hash());
            insertAction(action);
            audit.transition(context.actor().username(), PrivacyDsarAudit.SCOPED, request.getRequestNo(),
                    PrivacyCaseState.IDENTITY_VERIFIED.name(), PrivacyCaseState.SCOPED.name());
            return new PrivacyDsarViews.Transition(requestId, request.getRequestNo(), PrivacyCaseState.SCOPED.name(),
                    version + 1);
        });
    }

    /** 進められない case を BLOCKED として記録する（本人不明・複数候補等）。 */
    public PrivacyDsarViews.Transition block(long requestId, PrivacyDsarCommands.Closure command) {
        return close(requestId, command, PrivacyCaseState.BLOCKED);
    }

    /** 取下げ等を CANCELLED として記録する。 */
    public PrivacyDsarViews.Transition cancel(long requestId, PrivacyDsarCommands.Closure command) {
        return close(requestId, command, PrivacyCaseState.CANCELLED);
    }

    private PrivacyDsarViews.Transition close(long requestId, PrivacyDsarCommands.Closure command,
                                              PrivacyCaseState target) {
        Context context = beginManage();
        long version = requireVersion(command == null ? null : command.rowVersion());
        PrivacyDsarVocabulary.CloseReason reason = PrivacyDsarVocabulary
                .parse(PrivacyDsarVocabulary.CloseReason.class, command.reasonCode())
                .filter(candidate -> candidate.target() == target)
                .orElseThrow(PrivacyRequestService::invalid);
        String evidenceRef = optionalReference(command.evidenceRef());
        return execute(() -> {
            PrivacyRequest request = load(context.tenantId(), requestId);
            PrivacyCaseState current = PrivacyDsarVocabulary.state(request.getStatus())
                    .filter(PrivacyDsarVocabulary.CLOSABLE_STATES::contains)
                    .orElseThrow(PrivacyRequestService::conflict);
            requireState(request, current, version);
            LocalDateTime now = now();
            cas(context.tenantId(), requestId, current, version, now, update -> update
                    .set(PrivacyRequest::getStatus, target.name()));
            insertAction(manualAction(context.tenantId(), requestId, "DSAR-CLOSE:" + requestId + ":" + (version + 1),
                    target == PrivacyCaseState.BLOCKED ? ACTION_STATUS_BLOCKED : ACTION_STATUS_SUCCEEDED,
                    reason.name(), evidenceRef, now.truncatedTo(ChronoUnit.MICROS)));
            audit.transition(context.actor().username(), PrivacyDsarAudit.CLOSED, request.getRequestNo(),
                    current.name(), target.name());
            return new PrivacyDsarViews.Transition(requestId, request.getRequestNo(), target.name(), version + 1);
        });
    }

    // ---------------------------------------------------------------- 内部

    private Context begin(String action) {
        accessGuard.requireAllowed(PrivacyAccessGuard.Capability.DSAR, action);
        Actor actor = actorSource.get();
        return new Context(actor, requireCallerTenant(actor));
    }

    private Context beginManage() {
        Context context = begin(MANAGE_ACTION);
        if (context.actor().userId() == null || context.actor().userId() <= 0) {
            throw BusinessException.of(403, "error.accessDenied");
        }
        return context;
    }

    private PrivacyDsarDecisions.Readiness requireDecisions() {
        PrivacyDsarDecisions.Readiness readiness = decisions.evaluate();
        if (!readiness.ready()) {
            throw BusinessException.of(409, ERROR_DECISION_UNKNOWN);
        }
        return readiness;
    }

    private <T> T execute(Supplier<T> work) {
        return transactions.execute(status -> work.get());
    }

    /**
     * request 行の CAS。現状態と版が一致する行だけを更新し、版を1つ進める。0件なら 409（別の操作が先に進めた・状態が違う）。
     */
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

    private static void requireState(PrivacyRequest request, PrivacyCaseState expected, long version) {
        if (!expected.name().equals(request.getStatus()) || request.getRowVersion() == null
                || request.getRowVersion() != version) {
            throw conflict();
        }
    }

    private PrivacyRequest load(String tenantId, long requestId) {
        PrivacyRequest request = find(tenantId, requestId);
        if (request == null) {
            throw BusinessException.of(404, ERROR_NOT_FOUND);
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

    private List<PrivacyRequestSubjectLink> links(String tenantId, long requestId) {
        return linkMapper.selectList(new LambdaQueryWrapper<PrivacyRequestSubjectLink>()
                .eq(PrivacyRequestSubjectLink::getTenantId, tenantId)
                .eq(PrivacyRequestSubjectLink::getRequestId, requestId)
                .orderByAsc(PrivacyRequestSubjectLink::getId));
    }

    private List<PrivacyRequestSubjectLink> selectedLinks(String tenantId, long requestId) {
        return linkMapper.selectList(new LambdaQueryWrapper<PrivacyRequestSubjectLink>()
                .eq(PrivacyRequestSubjectLink::getTenantId, tenantId)
                .eq(PrivacyRequestSubjectLink::getRequestId, requestId)
                .eq(PrivacyRequestSubjectLink::getLinkState, PrivacyDsarVocabulary.LinkState.SELECTED.name()));
    }

    private static PrivacyAction manualAction(String tenantId, long requestId, String idempotencyKey, String status,
                                              String reasonCode, String evidenceRef, LocalDateTime createdAt) {
        PrivacyAction action = new PrivacyAction();
        action.setTenantId(tenantId);
        action.setRequestId(requestId);
        action.setActionType(ACTION_TYPE_MANUAL);
        action.setStatus(status);
        action.setIdempotencyKey(idempotencyKey);
        action.setBlockedReason(reasonCode);
        action.setEvidenceRef(evidenceRef);
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

    /** 候補の表示（subject 参照と記録の件数だけ。provider の照会に失敗した候補は件数を出さない）。 */
    private PrivacyDsarViews.Candidate candidateView(String tenantId, PrivacyRequestSubjectLink link) {
        Optional<PrivacySubjectRef> subject = PrivacySubjectRef.parse(link.getSubjectRef());
        boolean available = false;
        List<PrivacyDsarViews.FootprintEntry> entries = List.of();
        if (subject.isPresent()) {
            try {
                entries = footprint(tenantId, subject.get()).entries();
                available = true;
            } catch (BusinessException e) {
                available = false;
            }
        }
        return new PrivacyDsarViews.Candidate(link.getId(), link.getSubjectRef(),
                subject.map(ref -> ref.type().name()).orElse(null), link.getLinkState(), link.getResolutionCode(),
                display(link.getResolvedAt()), available, entries);
    }

    /** subject の記録の所在（provider・data element ごとの件数。行の値は持ち出さない）。 */
    private Footprint footprint(String tenantId, PrivacySubjectRef subject) {
        Map<String, Map<String, Integer>> counts = new TreeMap<>();
        int records = 0;
        for (ProductionPrivacyProvider provider : registry.providers()) {
            List<PrivacyRecord> found;
            try {
                found = provider.findBySubject(tenantId, subject);
            } catch (RuntimeException e) {
                log.warn("[privacy-dsar] providerを照会できません: provider={} exceptionClass={}",
                        provider.providerId(), e.getClass().getSimpleName());
                throw BusinessException.of(503, ERROR_SOURCE_UNAVAILABLE);
            }
            for (PrivacyRecord record : found) {
                records++;
                for (String elementId : record.dataElementIds()) {
                    counts.computeIfAbsent(provider.providerId(), key -> new TreeMap<>())
                            .merge(elementId, 1, Integer::sum);
                }
            }
        }
        List<PrivacyDsarViews.FootprintEntry> entries = new ArrayList<>();
        counts.forEach((providerId, elements) -> elements.forEach((elementId, count) ->
                entries.add(new PrivacyDsarViews.FootprintEntry(providerId, elementId, count,
                        registry.deliveryClassOf(elementId).name()))));
        return new Footprint(records, List.copyOf(entries));
    }

    /** 照会者の tenant（principal に束縛された値）。権限評価の設定 tenant と一致しなければ403。 */
    private String requireCallerTenant(Actor actor) {
        String callerTenant = actor == null ? null : actor.tenantId();
        String authorizationTenant = oidcProperties != null && StringUtils.hasText(oidcProperties.getTenantId())
                ? oidcProperties.getTenantId() : "default";
        if (!StringUtils.hasText(callerTenant) || !authorizationTenant.equals(callerTenant)) {
            throw BusinessException.of(403, "error.accessDenied");
        }
        return callerTenant;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock.withZone(PrivacyDecisionBinding.BUSINESS_ZONE));
    }

    private LocalDateTime receivedAt(String raw, LocalDateTime now) {
        if (!StringUtils.hasText(raw)) {
            return now;
        }
        LocalDateTime parsed;
        try {
            parsed = LocalDateTime.parse(raw.trim(), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException e) {
            throw invalid();
        }
        if (parsed.isAfter(now) || parsed.isBefore(now.minusDays(MAX_RECEIVED_AGE_DAYS))) {
            throw invalid();
        }
        return parsed;
    }

    private static String newRequestNo(LocalDate date) {
        StringBuilder value = new StringBuilder("DSAR-").append(REQUEST_NO_DATE.format(date)).append('-');
        for (int i = 0; i < REQUEST_NO_RANDOM_LENGTH; i++) {
            value.append(REQUEST_NO_ALPHABET[RANDOM.nextInt(REQUEST_NO_ALPHABET.length)]);
        }
        return value.toString();
    }

    private static String resolutionCode(String raw, PrivacyDsarVocabulary.LinkState target) {
        if (!StringUtils.hasText(raw)) {
            return target == PrivacyDsarVocabulary.LinkState.SELECTED ? DEFAULT_SELECTED_CODE : DEFAULT_REJECTED_CODE;
        }
        if (!PrivacyDsarVocabulary.CODE.matcher(raw).matches()) {
            throw invalid();
        }
        return raw;
    }

    private static long requireVersion(Long version) {
        if (version == null || version < 0) {
            throw invalid();
        }
        return version;
    }

    private static String requireReference(String raw) {
        if (raw == null || !PrivacyDsarVocabulary.REFERENCE.matcher(raw).matches()) {
            throw invalid();
        }
        return raw;
    }

    private static String optionalReference(String raw) {
        return StringUtils.hasText(raw) ? requireReference(raw) : null;
    }

    private static long version(PrivacyRequest request) {
        return request.getRowVersion() == null ? 0L : request.getRowVersion();
    }

    private static String display(LocalDateTime value) {
        return value == null ? null : DISPLAY_TIME.format(value);
    }

    private static String date(LocalDate value) {
        return value == null ? null : value.toString();
    }

    private static BusinessException invalid() {
        return BusinessException.of(400, ERROR_INVALID);
    }

    private static BusinessException conflict() {
        return BusinessException.of(409, ERROR_CONFLICT);
    }

    /** subject の記録の件数。 */
    private record Footprint(int recordCount, List<PrivacyDsarViews.FootprintEntry> entries) {
    }

    private record Context(Actor actor, String tenantId) {
    }

    /** 操作者（tenant・sys_user.id・username）。 */
    record Actor(String tenantId, Long userId, String username) {
        static Actor fromSecurityContext() {
            return new Actor(SecurityUtils.currentTenantId(), SecurityUtils.currentUserId(),
                    SecurityUtils.currentUsername());
        }
    }
}
