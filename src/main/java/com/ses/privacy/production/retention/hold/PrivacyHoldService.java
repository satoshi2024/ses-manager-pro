package com.ses.privacy.production.retention.hold;

import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.config.OidcSecurityProperties;
import com.ses.privacy.foundation.JournalConflictException;
import com.ses.privacy.foundation.PrivacyJournalPort;
import com.ses.privacy.foundation.PrivacyJournalPort.JournalAck;
import com.ses.privacy.foundation.PrivacyJournalPort.JournalCommand;
import com.ses.privacy.foundation.PrivacyJournalPort.JournalEvent;
import com.ses.privacy.foundation.PrivacyJournalPort.JournalHead;
import com.ses.privacy.foundation.PrivacyJournalPort.JournalPartition;
import com.ses.privacy.production.PrivacyAccessGuard;
import com.ses.privacy.production.provider.PrivacySubjectRef;
import com.ses.privacy.production.retention.catalog.SubjectAttribution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * NF07 hold の開始と一覧（production-bridge-phased-plan.md §3.4.4。解除は {@link PrivacyHoldReleaseService}＝NF07-REL）。
 *
 * <p>開始の手順:
 * <ol>
 *   <li>入口で {@link PrivacyAccessGuard}（OFF は404、{@code privacy.hold.manage} が無ければ403）。</li>
 *   <li>D-05・{@code privacy.hold.allowed-target-types}・{@code privacy.clock.max-skew}（{@link PrivacyHoldDecisions}）。
 *       欠ければ 409 {@code error.privacy.decisionUnknown}。対象種別が許可外なら 409。</li>
 *   <li>要求の検査（400）、Journal の可用性（profile に関係なく起動時検査が {@code AVAILABLE} でなければ 503）、
 *       SUBJECT hold では帰属できない element ごとの選択（既定値なし。欠ければ 400）。</li>
 *   <li>独立 transaction で追随位置の行を作る／lock し、同じ対象の ACTIVE な hold が無いこと・{@code DATA_ELEMENT_HOLD} を
 *       選んだ element に ACTIVE な DATA_ELEMENT hold があることを確かめ、pending を CAS で記録し、required 監査（開始要求・
 *       element ごとの選択）を書いて commit する。CAS が0件なら 409 {@code error.privacy.holdStartInProgress}。</li>
 *   <li>Journal に HOLD_STARTED を append する（command の {@code request_hash} が対象・理由 hash・ticket・開始者・D-05・選択を
 *       束縛する）。ACK 不明なら {@code pending_since} を CAS で進めてから同じ command で1回だけ再送し（CAS が0件なら再送しない）、
 *       なお不明なら pending を残して 503（reconciler に任せる）。revision の競合は、held なら
 *       {@code error.privacy.holdAlreadyActive}、それ以外は {@code error.privacy.holdStartInProgress}（再試行）の 409。</li>
 *   <li>ACK 後に chain を検証し、別の独立 transaction で projection を書き、追随位置を進め、pending を消す（{@link PrivacyHoldProjector}）。</li>
 * </ol>
 * 先に書く pending は成功応答ではなく、gate を fail-closed に倒すための印である。監査本文と log に subject 識別子を入れない。
 * 本 service は application の transaction の中から呼んではならない（本番 adapter が ambient transaction を拒否するため）。
 */
public class PrivacyHoldService {

    private static final Logger log = LoggerFactory.getLogger(PrivacyHoldService.class);

    static final String VIEW_ACTION = "privacy.hold.view";
    static final String MANAGE_ACTION = "privacy.hold.manage";
    /**
     * reconciler が Journal から復元した行の理由コード・ticket の印（Journal には request hash しか無く、元の値を復元できないため）。
     * 開始時の理由コード・ticket・D-05 の決定ID・選択は、hold ID で開始要求の監査（{@code START_REQUESTED}・{@code START_REASON}・
     * {@code CHOICE}）から引ける。理由本文とその hash は監査に残さないため、復元した行の理由 hash は再現できない。
     */
    static final String RECONCILED = "RECONCILED";
    static final String LOG_CODE_ACK_UNKNOWN = "NF07_PRIVACY_HOLD_ACK_UNKNOWN";
    static final int MAX_REASON_TEXT_LENGTH = 2000;
    private static final Pattern REASON_CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,49}");
    private static final Pattern TICKET_REF = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:#/-]{0,99}");
    private static final String REQUEST_DOMAIN = "NF07-HOLD-REQUEST-V1";
    /** 画面の日時表示（JST。他の画面と同じ。Review P3-11）。 */
    private static final java.time.format.DateTimeFormatter DISPLAY_TIME =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(PrivacyHoldProjectionStore.ZONE);

    /** 同じ実体の別 element（片方の DATA_ELEMENT hold では他方を守れない組。F2 delta Review P3-G）。 */
    static final List<PrivacyHoldOverview.RelatedElements> RELATED_ELEMENTS = List.of(
            new PrivacyHoldOverview.RelatedElements("RESUME_INGESTION_ORIGINAL", "DOCUMENT:RESUME_INGESTION",
                    "privacyHold.related.resumeLedgerCopy"));

    private final PrivacyAccessGuard accessGuard;
    private final OidcSecurityProperties oidcProperties;
    private final PrivacyHoldDecisions decisions;
    private final PrivacyHoldJournal journal;
    private final PrivacyHoldProjectionStore store;
    private final HoldSubjectResolver resolver;
    private final PrivacyHoldGate gate;
    private final PrivacyHoldProjector projector;
    private final PrivacyHoldAudit audit;
    private final TransactionTemplate requiresNew;
    private final Clock clock;
    private final Supplier<Actor> actorSource;
    private final BooleanSupplier releaseOpen;

    PrivacyHoldService(PrivacyAccessGuard accessGuard, OidcSecurityProperties oidcProperties,
                       PrivacyHoldDecisions decisions, PrivacyHoldJournal journal, PrivacyHoldProjectionStore store,
                       HoldSubjectResolver resolver, PrivacyHoldGate gate, PrivacyHoldProjector projector,
                       PrivacyHoldAudit audit, TransactionTemplate requiresNew, Clock clock, Supplier<Actor> actorSource) {
        this(accessGuard, oidcProperties, decisions, journal, store, resolver, gate, projector, audit, requiresNew, clock,
                actorSource, null);
    }

    /**
     * @param releaseOpen 解除の入口が開いているか（画面の表示だけに使う。null なら常に false。NF07-REL）
     */
    PrivacyHoldService(PrivacyAccessGuard accessGuard, OidcSecurityProperties oidcProperties,
                       PrivacyHoldDecisions decisions, PrivacyHoldJournal journal, PrivacyHoldProjectionStore store,
                       HoldSubjectResolver resolver, PrivacyHoldGate gate, PrivacyHoldProjector projector,
                       PrivacyHoldAudit audit, TransactionTemplate requiresNew, Clock clock, Supplier<Actor> actorSource,
                       BooleanSupplier releaseOpen) {
        this.releaseOpen = releaseOpen == null ? () -> false : releaseOpen;
        this.accessGuard = Objects.requireNonNull(accessGuard, "accessGuard");
        this.oidcProperties = oidcProperties;
        this.decisions = Objects.requireNonNull(decisions, "decisions");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.store = Objects.requireNonNull(store, "store");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.projector = Objects.requireNonNull(projector, "projector");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.requiresNew = Objects.requireNonNull(requiresNew, "requiresNew");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.actorSource = actorSource == null ? Actor::fromSecurityContext : actorSource;
    }

    /** 画面の入口（{@code GET /privacy/holds}）の再認可。 */
    public void requirePageAccess() {
        accessGuard.requireAllowed(PrivacyAccessGuard.Capability.RETENTION, VIEW_ACTION);
    }

    /** hold の一覧と開始の前提。 */
    public PrivacyHoldOverview overview() {
        accessGuard.requireAllowed(PrivacyAccessGuard.Capability.RETENTION, VIEW_ACTION);
        Actor viewer = actorSource.get();
        String tenantId = requireCallerTenant(viewer);
        PrivacyHoldDecisions.Readiness readiness = decisions.evaluate();
        PrivacyHoldJournal.Availability availability = journal.availability();
        // 画面の表示は破壊的経路の skip ではないため、gate の UNAVAILABLE の metric・WARN に数えない（Review P3-4）
        PrivacyHoldGate.Decision decision = gate.inspect(tenantId);
        List<PrivacyHoldProjectionStore.HoldRow> holds;
        Map<String, SubjectAttribution> catalog;
        try {
            holds = store.holds(tenantId, false);
            catalog = resolver.catalog(tenantId);
        } catch (RuntimeException e) {
            log.warn("[privacy-hold] hold一覧を照会できません: exceptionClass={}", e.getClass().getSimpleName());
            throw BusinessException.of(503, "error.privacy.sourceUnavailable");
        }
        Set<String> heldElements = new LinkedHashSet<>();
        List<PrivacyHoldOverview.HoldEntry> entries = new ArrayList<>();
        holds.stream()
                .sorted(Comparator.comparing(PrivacyHoldProjectionStore.HoldRow::requestedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .forEach(hold -> {
                    if (hold.active() && hold.targetType() == PrivacyHoldTargetType.DATA_ELEMENT) {
                        heldElements.add(hold.dataElementId());
                    }
                    entries.add(new PrivacyHoldOverview.HoldEntry(hold.holdId(),
                            hold.targetType() == null ? null : hold.targetType().name(), hold.subjectRef(),
                            hold.dataElementId(), hold.status(), hold.reasonCode(), hold.ticketRef(),
                            hold.requestedAt() == null ? null : DISPLAY_TIME.format(hold.requestedAt()), hold.journalRevision(),
                            RECONCILED.equals(hold.reasonCode()), hold.journalEventHash(),
                            hold.releasedAt() == null ? null : DISPLAY_TIME.format(hold.releasedAt()),
                            hold.partitionRef(), PrivacyHoldReleaseService.starterRef(hold)));
                });
        List<PrivacyHoldOverview.ElementEntry> unattributable = new ArrayList<>();
        for (String elementId : HoldSubjectResolver.unattributableElementIds(catalog)) {
            Long count;
            try {
                count = resolver.tenantRowCount(tenantId, elementId);
            } catch (RuntimeException e) {
                count = null;
            }
            unattributable.add(new PrivacyHoldOverview.ElementEntry(elementId, count, heldElements.contains(elementId)));
        }
        List<String> missing = new ArrayList<>(readiness.missing());
        if (availability != PrivacyHoldJournal.Availability.AVAILABLE) {
            missing.add("journal");
        }
        List<PrivacyHoldOverview.RelatedElements> related = RELATED_ELEMENTS.stream()
                .filter(pair -> catalog.containsKey(pair.dataElementId()) || catalog.containsKey(pair.relatedDataElementId()))
                .toList();
        List<String> holdable = catalog.entrySet().stream()
                .filter(entry -> entry.getValue() != SubjectAttribution.NO_PERSONAL_DATA)
                .map(Map.Entry::getKey).sorted().toList();
        return new PrivacyHoldOverview(decision.state().name(), decision.reason(), availability.name(),
                missing.isEmpty(), List.copyOf(missing),
                readiness.allowedTargetTypes().stream().map(Enum::name).sorted().toList(),
                List.copyOf(entries), List.copyOf(unattributable), HoldSubjectResolver.unclassifiedElementIds(catalog),
                related, holdable, releaseOpenQuietly(), tenantId,
                viewer.userId() == null || viewer.userId() <= 0 ? null
                        : PrivacyHoldReleaseService.REQUESTER_PREFIX + viewer.userId());
    }

    private boolean releaseOpenQuietly() {
        try {
            return releaseOpen.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** hold を開始する。 */
    public PrivacyHoldStartResult start(PrivacyHoldStartRequest request) {
        accessGuard.requireAllowed(PrivacyAccessGuard.Capability.RETENTION, MANAGE_ACTION);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("hold開始はapplication transactionの外から呼び出してください");
        }
        Actor actor = actorSource.get();
        String tenantId = requireCallerTenant(actor);
        if (actor.userId() == null || actor.userId() <= 0) {
            throw BusinessException.of(403, "error.accessDenied");
        }
        PrivacyHoldDecisions.Readiness readiness = decisions.evaluate();
        if (!readiness.ready()) {
            throw BusinessException.of(409, "error.privacy.decisionUnknown");
        }
        if (request == null) {
            throw BusinessException.of(400, "error.privacy.holdInvalidRequest");
        }
        PrivacyHoldTargetType type = PrivacyHoldTargetType.parse(request.targetType())
                .orElseThrow(() -> BusinessException.of(400, "error.privacy.holdInvalidTarget"));
        if (!readiness.allowedTargetTypes().contains(type)) {
            throw BusinessException.of(409, "error.privacy.holdTargetTypeNotAllowed");
        }
        Validated validated = validate(request, type);
        if (journal.availability() != PrivacyHoldJournal.Availability.AVAILABLE) {
            throw BusinessException.of(503, "error.privacy.journalUnavailable");
        }
        Map<String, SubjectAttribution> catalog;
        try {
            catalog = resolver.catalog(tenantId);
        } catch (RuntimeException e) {
            throw BusinessException.of(503, "error.privacy.sourceUnavailable");
        }
        Map<String, PrivacyHoldChoice> choices = Map.of();
        if (type == PrivacyHoldTargetType.DATA_ELEMENT) {
            SubjectAttribution attribution = catalog.get(validated.target());
            if (attribution == null || attribution == SubjectAttribution.NO_PERSONAL_DATA) {
                throw BusinessException.of(400, "error.privacy.holdInvalidTarget");
            }
        } else {
            choices = validateChoices(request.unattributableChoices(), HoldSubjectResolver.unattributableElementIds(catalog));
        }

        PrivacyHoldPartitions.Ids ids = PrivacyHoldPartitions.newIds();
        Instant requestedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        JournalPartition partition = new JournalPartition(tenantId, validated.partitionRef());
        // adapter は予約の前に1回だけ取り、append（再送を含む）まで同じものを使う。append の経路で可用性と instance の比較を
        // 評価し直さないため、比較の I/O が pending_since と append の開始の間に入らない（A1H-1 delta review P2-N1）
        PrivacyJournalPort port;
        JournalHead head;
        try {
            port = journal.port();
            head = port.head(partition);
        } catch (RuntimeException e) {
            throw BusinessException.of(503, "error.privacy.journalUnavailable");
        }
        if (head == null) {
            throw BusinessException.of(503, "error.privacy.journalUnavailable");
        }
        if (head.held()) {
            throw BusinessException.of(409, "error.privacy.holdAlreadyActive");
        }
        String requestHash = requestHash(tenantId, ids.holdId(), type, validated, actor.userId(), requestedAt,
                readiness.d05DecisionId(), choices);
        JournalCommand command = JournalCommand.create(ids.commandId(), partition, head.revision(),
                PrivacyJournalPort.CommandType.HOLD_STARTED, ids.holdId(), null, requestHash, null, 0, null);

        reservePending(tenantId, ids, type, validated, choices, requestHash, requestedAt, readiness.d05DecisionId(), actor);

        JournalAck ack;
        try {
            ack = appendWithRefreshedReplay(port, command, tenantId, ids.commandId());
        } catch (JournalConflictException e) {
            // Journal の transaction 内で拒否された（append されていない）ので pending を戻してよい
            releasePending(tenantId, ids, actor);
            // held なら同じ対象の hold が既にある。それ以外（revision の競合）は再試行を求める（Review P3-7）
            throw BusinessException.of(409, headHeldQuietly(partition)
                    ? "error.privacy.holdAlreadyActive" : "error.privacy.holdStartInProgress");
        } catch (RuntimeException e) {
            ackUnknown(ids, actor);
            throw BusinessException.of(503, "error.privacy.holdAckUnknown");
        }

        JournalEvent event;
        try {
            event = projector.acknowledgedEvent(tenantId, validated.partitionRef(), ids.commandId());
        } catch (RuntimeException e) {
            ackUnknown(ids, actor);
            throw BusinessException.of(503, "error.privacy.holdAckUnknown");
        }
        if (event == null || !PrivacyHoldProjector.isHoldStart(event, ids.holdId()) || event.revision() != ack.revision()
                || !Objects.equals(event.eventHash(), ack.eventHash()) || !requestHash.equals(event.requestHash())) {
            ackUnknown(ids, actor);
            throw BusinessException.of(503, "error.privacy.holdAckUnknown");
        }
        PrivacyHoldProjectionStore.NewHold hold = new PrivacyHoldProjectionStore.NewHold(ids.holdId(), type,
                validated.target(), validated.reasonCode(), validated.reasonHash(), validated.ticketRef(), actor.userId(),
                requestedAt, ack.revision(), ack.eventHash());
        try {
            projector.project(tenantId, hold, ids.commandId(), actor.username(), PrivacyHoldAudit.STARTED,
                    PrivacyHoldAudit.fields("hold", ids.holdId(), "target", type.name(),
                            "element", type == PrivacyHoldTargetType.DATA_ELEMENT ? validated.target() : null,
                            "revision", Long.toString(ack.revision()), "event", ack.eventHash().substring(0, 16)));
        } catch (RuntimeException e) {
            // ACK 済み。pending を残し、reconciler が projection を補う
            ackUnknown(ids, actor);
            throw BusinessException.of(503, "error.privacy.holdAckUnknown");
        }
        return new PrivacyHoldStartResult(ids.holdId(), type.name(), PrivacyHoldProjectionStore.STATUS_ACTIVE,
                ack.revision());
    }

    private void reservePending(String tenantId, PrivacyHoldPartitions.Ids ids, PrivacyHoldTargetType type,
                                Validated validated, Map<String, PrivacyHoldChoice> choices, String requestHash,
                                Instant requestedAt, String d05DecisionId, Actor actor) {
        String journalId = journal.journalId();
        try {
            requiresNew.executeWithoutResult(status -> {
                store.ensureStateLocked(tenantId, journalId, requestedAt);
                PrivacyHoldProjectionStore.StateRow state = store.lockState(tenantId, journalId)
                        .orElseThrow(() -> BusinessException.of(503, "error.privacy.sourceUnavailable"));
                if (state.pendingCommandId() != null) {
                    throw BusinessException.of(409, "error.privacy.holdStartInProgress");
                }
                if (store.activeHoldExists(tenantId, type, validated.target())) {
                    throw BusinessException.of(409, "error.privacy.holdAlreadyActive");
                }
                for (Map.Entry<String, PrivacyHoldChoice> choice : choices.entrySet()) {
                    if (choice.getValue() == PrivacyHoldChoice.DATA_ELEMENT_HOLD
                            && !store.activeHoldExists(tenantId, PrivacyHoldTargetType.DATA_ELEMENT, choice.getKey())) {
                        throw BusinessException.of(409, "error.privacy.holdChoicesStale");
                    }
                }
                // 監査は同じ transaction に入る（CAS が0件なら監査ごと戻る）。pending_since の取得と CAS は監査の後に置き、
                // 監査の insert の時間も reconciler が待つ append の在途時間に含めない（P2-N1）
                audit.record(actor.username(), PrivacyHoldAudit.START_REQUESTED, PrivacyHoldAudit.fields(
                        "hold", ids.holdId(), "target", type.name(),
                        "element", type == PrivacyHoldTargetType.DATA_ELEMENT ? validated.target() : null,
                        "d05", d05DecisionId, "request", requestHash.substring(0, 16),
                        "choices", Integer.toString(choices.size())), 202, true);
                // 理由コードと ticket は別の行にする（1行の上限 500 文字に収めるため）。理由本文と理由 hash は監査に残さない
                audit.record(actor.username(), PrivacyHoldAudit.START_REASON, PrivacyHoldAudit.fields(
                        "hold", ids.holdId(), "reason", validated.reasonCode(), "ticket", validated.ticketRef()),
                        202, true);
                for (Map.Entry<String, PrivacyHoldChoice> choice : choices.entrySet()) {
                    audit.record(actor.username(), PrivacyHoldAudit.CHOICE, PrivacyHoldAudit.fields(
                            "hold", ids.holdId(), "element", choice.getKey(), "choice", choice.getValue().name()),
                            202, true);
                }
                if (type == PrivacyHoldTargetType.DATA_ELEMENT) {
                    audit.record(actor.username(), PrivacyHoldAudit.DATA_ELEMENT_ACKNOWLEDGED, PrivacyHoldAudit.fields(
                            "hold", ids.holdId(), "element", validated.target(), "acknowledged", "purgeStopUntilRelease"),
                            202, true);
                }
                // pending_since は lock を取った後・監査の後・CAS の直前の時刻（Journal の head 照会・lock 待ち・監査の時間を、
                // reconciler が待つ append の在途時間に含めない。Review P2-1・P2-N1）。requestedAt は request hash と projection
                // にだけ使う。ここから append の開始までに残るのはこの transaction の commit だけ（plan §3.4.4 の余裕）
                Instant pendingSince = clock.instant();
                if (store.recordPending(tenantId, journalId, ids.commandId(), pendingSince) != 1) {
                    throw BusinessException.of(409, "error.privacy.holdStartInProgress");
                }
            });
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("[privacy-hold] hold開始の予約を記録できません: exceptionClass={}", e.getClass().getSimpleName());
            throw BusinessException.of(503, "error.privacy.sourceUnavailable");
        }
    }

    /**
     * append し、ACK 不明なら同じ command で1回だけ再送する（先の append が確定していれば元の ACK が返り、二重に append しない）。
     *
     * <p>再送の直前に {@code pending_since} を CAS で現在時刻へ進める（Review P2-1 の方針 a）。reconciler が「Journal に command が
     * 無い」pending を消してよいのは {@code pending_since} から append の在途時間の上限（1回分）を過ぎた後なので、再送の在途時間も
     * その窓に収まる。CAS が0件（reconciler が既に pending を消した）・失敗なら再送せず、最初の ACK 不明をそのまま返す
     * （先の append が後から確定しても、reconciler の追い付きと gate の追随遅れ判定が拾う）。
     *
     * <p>{@code port} は head の照会に使ったもの（予約の前に取得済み）。ここで {@link PrivacyHoldJournal#port()} を呼び直すと、
     * 可用性の評価に伴う instance の比較（Journal pool の接続取得と2つの {@code @@server_uuid} 照会）が {@code pending_since}
     * の後・append の前に入り、在途時間の上限の外の時間が窓に加わる（P2-N1）。
     */
    private JournalAck appendWithRefreshedReplay(PrivacyJournalPort port, JournalCommand command, String tenantId,
                                                 String commandId) {
        try {
            return port.append(command);
        } catch (JournalConflictException e) {
            throw e;
        } catch (RuntimeException firstUnknown) {
            if (!refreshPendingSince(tenantId, commandId)) {
                throw firstUnknown;
            }
            return port.append(command);
        }
    }

    /**
     * 再送の直前に {@code pending_since} を進める。予約と同じ順序で、追随位置の行を {@code FOR UPDATE} で取り、自分の command が
     * まだ pending であることを確かめてから時計を読み、CAS で書く（P2-N1）。時計を先に読むと、gate の
     * {@code evaluateLocked}（破壊的経路の transaction が行 lock を終了まで持つ）を待つ時間だけ {@code pending_since} が
     * 再送の実際の開始より古くなり、その分 reconciler が再送の在途中に pending を消し得る。
     */
    private boolean refreshPendingSince(String tenantId, String commandId) {
        String journalId = journal.journalId();
        try {
            Integer updated = requiresNew.execute(status -> {
                PrivacyHoldProjectionStore.StateRow state = store.lockState(tenantId, journalId).orElse(null);
                if (state == null || !commandId.equals(state.pendingCommandId())) {
                    // reconciler が既に消した（または別の command に替わった）。再送しない
                    return 0;
                }
                return store.refreshPending(tenantId, journalId, commandId, clock.instant());
            });
            return updated != null && updated == 1;
        } catch (RuntimeException e) {
            log.warn("[privacy-hold] 再送前にpendingの時刻を進められないため再送しません: exceptionClass={}",
                    e.getClass().getSimpleName());
            return false;
        }
    }

    /** partition の head が held か（照会できなければ false。409 の文言の選択にだけ使う）。 */
    private boolean headHeldQuietly(JournalPartition partition) {
        try {
            JournalHead head = journal.port().head(partition);
            return head != null && head.held();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void releasePending(String tenantId, PrivacyHoldPartitions.Ids ids, Actor actor) {
        try {
            requiresNew.executeWithoutResult(status -> {
                store.clearPending(tenantId, journal.journalId(), ids.commandId());
                audit.record(actor.username(), PrivacyHoldAudit.START_REJECTED,
                        PrivacyHoldAudit.fields("hold", ids.holdId(), "reason", "JOURNAL_CONFLICT"), 409, false);
            });
        } catch (RuntimeException e) {
            // pending が残っても reconciler が「Journal に command が無い」ことを確かめてから消す
            log.warn("[privacy-hold] 拒否されたhold開始のpendingを戻せません: exceptionClass={}", e.getClass().getSimpleName());
        }
    }

    private void ackUnknown(PrivacyHoldPartitions.Ids ids, Actor actor) {
        log.warn("[privacy-hold] hold開始のACKを確認できません。pendingを残してreconcilerに任せます: code={}",
                LOG_CODE_ACK_UNKNOWN);
        try {
            requiresNew.executeWithoutResult(status -> audit.record(actor.username(), PrivacyHoldAudit.ACK_UNKNOWN,
                    PrivacyHoldAudit.fields("hold", ids.holdId()), 503, false));
        } catch (RuntimeException e) {
            log.warn("[privacy-hold] ACK不明の監査を記録できません: exceptionClass={}", e.getClass().getSimpleName());
        }
    }

    private Validated validate(PrivacyHoldStartRequest request, PrivacyHoldTargetType type) {
        String target;
        String partitionRef;
        if (type == PrivacyHoldTargetType.SUBJECT) {
            if (request.dataElementId() != null) {
                throw BusinessException.of(400, "error.privacy.holdInvalidTarget");
            }
            PrivacySubjectRef subject = PrivacySubjectRef.parse(request.subjectRef())
                    .orElseThrow(() -> BusinessException.of(400, "error.privacy.holdInvalidTarget"));
            target = subject.ref();
            partitionRef = PrivacyHoldPartitions.subjectPartition(target);
        } else {
            if (request.subjectRef() != null || (request.unattributableChoices() != null
                    && !request.unattributableChoices().isEmpty())) {
                throw BusinessException.of(400, "error.privacy.holdInvalidTarget");
            }
            target = request.dataElementId();
            partitionRef = PrivacyHoldPartitions.dataElementPartition(target);
            if (!Boolean.TRUE.equals(request.acknowledgePurgeStop())) {
                throw BusinessException.of(400, "error.privacy.holdAcknowledgeRequired");
            }
        }
        if (partitionRef == null) {
            throw BusinessException.of(400, "error.privacy.holdInvalidTarget");
        }
        String reasonCode = request.reasonCode();
        if (reasonCode == null || !REASON_CODE.matcher(reasonCode).matches() || RECONCILED.equals(reasonCode)) {
            throw BusinessException.of(400, "error.privacy.holdInvalidRequest");
        }
        String ticketRef = request.ticketRef();
        if (ticketRef == null || !TICKET_REF.matcher(ticketRef).matches() || RECONCILED.equals(ticketRef)) {
            throw BusinessException.of(400, "error.privacy.holdInvalidRequest");
        }
        String reasonText = request.reasonText();
        if (!StringUtils.hasText(reasonText) || reasonText.length() > MAX_REASON_TEXT_LENGTH) {
            throw BusinessException.of(400, "error.privacy.holdInvalidRequest");
        }
        return new Validated(target, partitionRef, reasonCode, sha256(reasonText), ticketRef);
    }

    /** 帰属できない element ごとの選択。過不足・重複・未知の値は拒否する（既定値へ倒さない）。 */
    static Map<String, PrivacyHoldChoice> validateChoices(List<PrivacyHoldStartRequest.ElementChoice> requested,
                                                          List<String> required) {
        Map<String, PrivacyHoldChoice> choices = new TreeMap<>();
        if (requested != null) {
            for (PrivacyHoldStartRequest.ElementChoice choice : requested) {
                if (choice == null || choice.dataElementId() == null || !required.contains(choice.dataElementId())) {
                    throw BusinessException.of(400, "error.privacy.holdInvalidRequest");
                }
                PrivacyHoldChoice value = PrivacyHoldChoice.parse(choice.choice())
                        .orElseThrow(() -> BusinessException.of(400, "error.privacy.holdChoiceRequired"));
                if (choices.put(choice.dataElementId(), value) != null) {
                    throw BusinessException.of(400, "error.privacy.holdInvalidRequest");
                }
            }
        }
        if (!choices.keySet().containsAll(required)) {
            throw BusinessException.of(400, "error.privacy.holdChoiceRequired");
        }
        return choices;
    }

    /** HOLD_STARTED command の request hash（対象・理由 hash・ticket・開始者・開始日時・D-05・選択を束縛する）。 */
    static String requestHash(String tenantId, String holdId, PrivacyHoldTargetType type, Validated validated,
                              long requestedBy, Instant requestedAt, String d05DecisionId,
                              Map<String, PrivacyHoldChoice> choices) {
        StringBuilder canonical = new StringBuilder();
        field(canonical, REQUEST_DOMAIN);
        field(canonical, tenantId);
        field(canonical, holdId);
        field(canonical, type.name());
        field(canonical, validated.target());
        field(canonical, validated.reasonCode());
        field(canonical, validated.reasonHash());
        field(canonical, validated.ticketRef());
        field(canonical, Long.toString(requestedBy));
        field(canonical, requestedAt.toString());
        field(canonical, d05DecisionId);
        field(canonical, Integer.toString(choices.size()));
        for (Map.Entry<String, PrivacyHoldChoice> choice : new TreeMap<>(choices).entrySet()) {
            field(canonical, choice.getKey());
            field(canonical, choice.getValue().name());
        }
        return sha256(canonical.toString());
    }

    private static void field(StringBuilder target, String value) {
        if (value == null) {
            target.append("-1:");
        } else {
            target.append(value.getBytes(StandardCharsets.UTF_8).length).append(':').append(value);
        }
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256が利用できません", e);
        }
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

    /** 検査済みの要求。 */
    record Validated(String target, String partitionRef, String reasonCode, String reasonHash, String ticketRef) {
    }

    /** 操作者（tenant・sys_user.id・username）。 */
    record Actor(String tenantId, Long userId, String username) {
        static Actor fromSecurityContext() {
            return new Actor(SecurityUtils.currentTenantId(), SecurityUtils.currentUserId(), SecurityUtils.currentUsername());
        }
    }
}
