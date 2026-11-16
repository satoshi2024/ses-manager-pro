package com.ses.privacy.production.retention.hold;

import com.ses.common.exception.BusinessException;
import com.ses.config.OidcSecurityProperties;
import com.ses.privacy.foundation.HoldReleaseAuthorityClaim;
import com.ses.privacy.foundation.HoldReleaseBinding;
import com.ses.privacy.foundation.HoldReleaseJournalPort;
import com.ses.privacy.foundation.HoldReleaseRejectedException;
import com.ses.privacy.foundation.HoldReleaseRejection;
import com.ses.privacy.foundation.HoldReleaseTrustRegistry;
import com.ses.privacy.foundation.HoldReleaseVerifier;
import com.ses.privacy.foundation.JournalConflictException;
import com.ses.privacy.foundation.PrivacyJournalPort;
import com.ses.privacy.foundation.PrivacyJournalPort.JournalAck;
import com.ses.privacy.foundation.PrivacyJournalPort.JournalCommand;
import com.ses.privacy.foundation.PrivacyJournalPort.JournalEvent;
import com.ses.privacy.foundation.PrivacyJournalPort.JournalHead;
import com.ses.privacy.foundation.PrivacyJournalPort.JournalPartition;
import com.ses.privacy.foundation.VerifiedHoldRelease;
import com.ses.privacy.production.PrivacyAccessGuard;
import com.ses.privacy.production.retention.catalog.SubjectAttribution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * NF07 hold の解除（NF07-REL、production-bridge-phased-plan.md §4、sf-w2-protocol.md §11.1）。実装は署名前に入れるが、
 * 開放は D-05・D-03 の署名後。
 *
 * <p>開放の gate（{@link #isOpen()}）: 次の全てが揃うまで、解除の入口は存在しない URL と同じ 404 を返す（理由は報告参照）。
 * <ul>
 *   <li>D-05（hold の種類と権限者）と D-03（署名鍵）の決定ID が、署名日・失効・placeholder を含めて有効（呼出しごとに評価）。</li>
 *   <li>verifier がある（{@code ProductionPrivacyComponents.holdReleaseVerifier} は prod profile でしか生成できない）。</li>
 *   <li>解除 authority の trust anchor が1件以上登録されている（第1期の registry は空。実 registry は NF07-TRUST）。</li>
 * </ul>
 * 加えて第1期の失効 provider は常に UNKNOWN のため、仮に上が揃っても verifier が {@code REVOCATION_UNKNOWN} で拒否する。
 *
 * <p>解除の手順:
 * <ol>
 *   <li>入口で {@link PrivacyAccessGuard}（OFF は404、{@code privacy.hold.release} が無ければ403）、開放の gate（404）、
 *       要求の検査（400）、hold の projection（無ければ404）、Journal の可用性（503）。</li>
 *   <li>同じ command ID が既に Journal にあれば、内容（束縛の digest と command の payload hash）が同じときだけ元の ACK を返す
 *       （projection が未反映なら補う）。内容が違えば 409。期限切れの proof の再送でも元の結果を返す（新しい状態変更を
 *       起こさないため）。</li>
 *   <li>信頼できる現在値（呼出し元の tenant・hold の partition・hold の開始の revision・理由本文の hash・現在の journal head・
 *       呼出し元）から束縛を組み立て、verifier に提示された束縛・2つの claim と照合させる（{@link HoldReleaseRejection}）。</li>
 *   <li>独立 transaction で追随位置の行を lock し、pending の CAS（hold 開始と同じ行。同じ tenant の開始・解除は1件ずつ）、
 *       hold 行の lock と hold revision の確認、DATA_ELEMENT hold の依存の確認、required 監査を書いて commit する。</li>
 *   <li>Journal に HOLD_RELEASED を append する（adapter が head と hold revision を CAS し、期限を確かめる）。ACK 不明なら
 *       {@code pending_since} を進めて同じ proof で1回だけ再送し、なお不明なら pending を残して 503（reconciler に任せる）。</li>
 *   <li>ACK 後に chain を検証し、別の独立 transaction で projection を RELEASED にし（解除日時は Journal の event 時刻）、
 *       追随位置を進め、pending を消す。</li>
 * </ol>
 * 解除した hold は gate の hold 集合に入らないため、D1〜D10 の経路は従来どおり動く。監査本文と log に subject 識別子を入れない。
 * 本 service は application の transaction の中から呼んではならない（本番 adapter が ambient transaction を拒否するため）。
 */
public class PrivacyHoldReleaseService {

    private static final Logger log = LoggerFactory.getLogger(PrivacyHoldReleaseService.class);

    static final String RELEASE_ACTION = "privacy.hold.release";
    /** 要求者・authority の主体の名前空間（{@code HoldReleaseTrustAnchor.principalRef} と同じ形）。 */
    static final String REQUESTER_PREFIX = "sys_user:";
    static final String LOG_CODE_ACK_UNKNOWN = "NF07_PRIVACY_HOLD_RELEASE_ACK_UNKNOWN";
    static final String LOG_CODE_REJECTED = "NF07_PRIVACY_HOLD_RELEASE_REJECTED";
    private static final Pattern REASON_CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,49}");
    private static final Pattern TICKET_REF = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:#/-]{0,99}");
    private static final Pattern AUTHORITY = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final int MAX_SIGNATURE_LENGTH = 512;

    private final PrivacyAccessGuard accessGuard;
    private final OidcSecurityProperties oidcProperties;
    private final PrivacyHoldDecisions decisions;
    private final PrivacyHoldJournal journal;
    private final PrivacyHoldProjectionStore store;
    private final HoldSubjectResolver resolver;
    private final PrivacyHoldProjector projector;
    private final PrivacyHoldAudit audit;
    private final TransactionTemplate requiresNew;
    private final Clock clock;
    private final Supplier<PrivacyHoldService.Actor> actorSource;
    private final HoldReleaseVerifier verifier;
    private final HoldReleaseTrustRegistry registry;

    PrivacyHoldReleaseService(PrivacyAccessGuard accessGuard, OidcSecurityProperties oidcProperties,
                              PrivacyHoldDecisions decisions, PrivacyHoldJournal journal,
                              PrivacyHoldProjectionStore store, HoldSubjectResolver resolver,
                              PrivacyHoldProjector projector, PrivacyHoldAudit audit, TransactionTemplate requiresNew,
                              Clock clock, Supplier<PrivacyHoldService.Actor> actorSource, HoldReleaseVerifier verifier,
                              HoldReleaseTrustRegistry registry) {
        this.accessGuard = Objects.requireNonNull(accessGuard, "accessGuard");
        this.oidcProperties = oidcProperties;
        this.decisions = Objects.requireNonNull(decisions, "decisions");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.store = Objects.requireNonNull(store, "store");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.projector = Objects.requireNonNull(projector, "projector");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.requiresNew = Objects.requireNonNull(requiresNew, "requiresNew");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.actorSource = actorSource == null ? PrivacyHoldService.Actor::fromSecurityContext : actorSource;
        this.verifier = verifier;
        this.registry = registry;
    }

    /** 解除の入口が開いているか（D-03・D-05・verifier・trust anchor。画面の表示にも使う）。判定できなければ false。 */
    public boolean isOpen() {
        try {
            return verifier != null && anchorsConfigured() && decisions.releaseReadiness().ready();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** hold を解除する。 */
    public PrivacyHoldReleaseResult release(String holdId, PrivacyHoldReleaseRequest request) {
        accessGuard.requireAllowed(PrivacyAccessGuard.Capability.RETENTION, RELEASE_ACTION);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("hold解除はapplication transactionの外から呼び出してください");
        }
        PrivacyHoldService.Actor actor = actorSource.get();
        String tenantId = requireCallerTenant(actor);
        if (actor.userId() == null || actor.userId() <= 0) {
            throw BusinessException.of(403, "error.accessDenied");
        }
        PrivacyHoldDecisions.ReleaseReadiness readiness = decisions.releaseReadiness();
        if (!readiness.ready() || verifier == null || !anchorsConfigured()) {
            // 開放の前（D-03・D-05 の署名前・placeholder・失効、trust anchor 未登録、prod 以外）は入口が無いのと同じ404
            throw new PrivacyAccessGuard.PrivacyEntryNotFoundException();
        }
        Validated validated = validate(holdId, request);
        PrivacyHoldProjectionStore.HoldRow hold;
        try {
            hold = store.hold(tenantId, holdId).orElse(null);
        } catch (RuntimeException e) {
            throw BusinessException.of(503, "error.privacy.sourceUnavailable");
        }
        if (hold == null) {
            throw BusinessException.of(404, "error.privacy.holdNotFound");
        }
        String partitionRef = hold.partitionRef();
        if (partitionRef == null || hold.targetType() == null) {
            throw BusinessException.of(503, "error.privacy.sourceUnavailable");
        }
        if (journal.availability() != PrivacyHoldJournal.Availability.AVAILABLE) {
            throw BusinessException.of(503, "error.privacy.journalUnavailable");
        }
        JournalPartition partition = new JournalPartition(tenantId, partitionRef);
        // adapter は予約の前に1回だけ取り、append（再送を含む）まで同じものを使う（hold 開始と同じ。A1H-1 delta review P2-N1）
        PrivacyJournalPort port;
        List<JournalEvent> events;
        JournalHead head;
        try {
            port = journal.port();
            events = List.copyOf(port.events(partition));
            head = port.head(partition);
        } catch (RuntimeException e) {
            throw BusinessException.of(503, "error.privacy.journalUnavailable");
        }
        if (!(port instanceof HoldReleaseJournalPort releasePort) || head == null) {
            throw BusinessException.of(503, "error.privacy.journalUnavailable");
        }
        String requesterRef = REQUESTER_PREFIX + actor.userId();
        String commandId = validated.presented().commandId();
        Optional<JournalEvent> prior = events.stream().filter(event -> commandId.equals(event.commandId())).findFirst();
        if (prior.isPresent()) {
            return replay(tenantId, hold, partition, validated, prior.get(), requesterRef, actor);
        }
        if (!hold.active()) {
            throw BusinessException.of(409, "error.privacy.holdNotActive");
        }
        Map<String, SubjectAttribution> catalog = null;
        if (hold.targetType() == PrivacyHoldTargetType.DATA_ELEMENT) {
            try {
                catalog = resolver.catalog(tenantId);
            } catch (RuntimeException e) {
                throw BusinessException.of(503, "error.privacy.sourceUnavailable");
            }
        }
        HoldReleaseBinding expected;
        try {
            expected = new HoldReleaseBinding(tenantId, partitionRef, holdId, hold.journalRevision(),
                    validated.reasonHash(), commandId, head.revision(), head.eventHash(), requesterRef);
        } catch (IllegalArgumentException e) {
            throw BusinessException.of(503, "error.privacy.sourceUnavailable");
        }
        VerifiedHoldRelease proof;
        try {
            proof = verifier.verify(validated.presented(), expected, hold.targetType().name(), validated.issuer(),
                    validated.approver());
        } catch (HoldReleaseRejectedException e) {
            BusinessException mapped = rejection(e.reason());
            rejected(actor, holdId, e.reason().name(), mapped.getCode());
            throw mapped;
        } catch (SecurityException e) {
            // verifier が prod 以外で動いた（生成は prod に限るため通常は起きない）。入口が無いのと同じ扱い
            throw new PrivacyAccessGuard.PrivacyEntryNotFoundException();
        } catch (RuntimeException e) {
            throw BusinessException.of(503, "error.privacy.sourceUnavailable");
        }

        reservePending(tenantId, hold, proof, validated, readiness, catalog, actor);

        JournalAck ack;
        try {
            ack = appendWithRefreshedReplay(releasePort, proof, tenantId, commandId);
        } catch (HoldReleaseRejectedException e) {
            // Journal の transaction 内で拒否された（append されていない）ので pending を戻してよい
            BusinessException mapped = rejection(e.reason());
            releasePending(tenantId, holdId, commandId, actor, e.reason().name(), mapped.getCode());
            throw mapped;
        } catch (JournalConflictException e) {
            releasePending(tenantId, holdId, commandId, actor, "JOURNAL_CONFLICT", 409);
            throw BusinessException.of(409, "error.privacy.holdReleaseStale");
        } catch (SecurityException e) {
            releasePending(tenantId, holdId, commandId, actor, "JOURNAL_REFUSED", 403);
            throw BusinessException.of(403, "error.privacy.holdReleaseProofRejected");
        } catch (RuntimeException e) {
            ackUnknown(holdId, actor);
            throw BusinessException.of(503, "error.privacy.holdReleaseAckUnknown");
        }

        JournalEvent event;
        try {
            event = projector.acknowledgedEvent(tenantId, partitionRef, commandId);
        } catch (RuntimeException e) {
            ackUnknown(holdId, actor);
            throw BusinessException.of(503, "error.privacy.holdReleaseAckUnknown");
        }
        if (event == null || !PrivacyHoldProjector.isHoldRelease(event, holdId) || event.revision() != ack.revision()
                || !Objects.equals(event.eventHash(), ack.eventHash())
                || !proof.binding().digest().equals(event.requestHash())) {
            ackUnknown(holdId, actor);
            throw BusinessException.of(503, "error.privacy.holdReleaseAckUnknown");
        }
        try {
            projector.projectRelease(tenantId, holdId, event.createdAt(), commandId, actor.username(),
                    PrivacyHoldAudit.RELEASED, releasedFields(hold, event, false));
        } catch (RuntimeException e) {
            // ACK 済み。pending を残し、reconciler が projection を補う
            ackUnknown(holdId, actor);
            throw BusinessException.of(503, "error.privacy.holdReleaseAckUnknown");
        }
        return new PrivacyHoldReleaseResult(holdId, PrivacyHoldProjectionStore.STATUS_RELEASED, ack.revision(),
                ack.replayed());
    }

    /**
     * 同じ command ID が既に Journal にある場合（同じ command の再送）。内容が同じなら元の ACK を返し、projection が未反映なら補う。
     * 内容の同一性は、提示された束縛が呼出し元・hold・理由本文に対して信頼できる値と一致し、その digest が event の
     * {@code request_hash} と、束縛から決まる command の payload hash が event の payload hash と一致することで判定する。
     */
    private PrivacyHoldReleaseResult replay(String tenantId, PrivacyHoldProjectionStore.HoldRow hold,
                                            JournalPartition partition, Validated validated, JournalEvent prior,
                                            String requesterRef, PrivacyHoldService.Actor actor) {
        HoldReleaseBinding presented = validated.presented();
        JournalCommand command = JournalCommand.create(presented.commandId(), partition,
                presented.expectedJournalRevision(), PrivacyJournalPort.CommandType.HOLD_RELEASED, presented.holdId(),
                null, presented.digest(), null, 0, null);
        boolean sameContent = PrivacyHoldProjector.isHoldRelease(prior, hold.holdId())
                && presented.tenantId().equals(tenantId) && presented.subjectRef().equals(partition.subjectRef())
                && presented.holdId().equals(hold.holdId()) && presented.requesterRef().equals(requesterRef)
                && presented.reasonHash().equals(validated.reasonHash())
                && presented.digest().equals(prior.requestHash())
                && command.payloadHash().equals(prior.payloadHash());
        if (!sameContent) {
            rejected(actor, hold.holdId(), "COMMAND_REUSED", 409);
            throw BusinessException.of(409, "error.privacy.holdReleaseConflict");
        }
        if (hold.active()) {
            // ACK 済みの解除が projection に未反映（ACK 不明の後の再送）。projection を補い、自分の pending なら消す
            try {
                projector.projectRelease(tenantId, hold.holdId(), prior.createdAt(), presented.commandId(),
                        actor.username(), PrivacyHoldAudit.RELEASED, releasedFields(hold, prior, true));
            } catch (RuntimeException e) {
                ackUnknown(hold.holdId(), actor);
                throw BusinessException.of(503, "error.privacy.holdReleaseAckUnknown");
            }
        }
        return new PrivacyHoldReleaseResult(hold.holdId(), PrivacyHoldProjectionStore.STATUS_RELEASED, prior.revision(),
                true);
    }

    private void reservePending(String tenantId, PrivacyHoldProjectionStore.HoldRow hold, VerifiedHoldRelease proof,
                                Validated validated, PrivacyHoldDecisions.ReleaseReadiness readiness,
                                Map<String, SubjectAttribution> catalog, PrivacyHoldService.Actor actor) {
        String journalId = journal.journalId();
        String commandId = proof.binding().commandId();
        try {
            requiresNew.executeWithoutResult(status -> {
                store.ensureStateLocked(tenantId, journalId, clock.instant());
                PrivacyHoldProjectionStore.StateRow state = store.lockState(tenantId, journalId)
                        .orElseThrow(() -> BusinessException.of(503, "error.privacy.sourceUnavailable"));
                // 同じ command の pending（ACK 不明の後の再送）だけは続けてよい。他の command（開始・解除）が pending なら409
                boolean resend = commandId.equals(state.pendingCommandId());
                if (state.pendingCommandId() != null && !resend) {
                    throw BusinessException.of(409, "error.privacy.holdReleaseInProgress");
                }
                PrivacyHoldProjectionStore.HoldRow locked = store.lockHold(tenantId, hold.holdId())
                        .orElseThrow(() -> BusinessException.of(404, "error.privacy.holdNotFound"));
                if (!locked.active()) {
                    throw BusinessException.of(409, "error.privacy.holdNotActive");
                }
                // hold revision の CAS（projection 側）。Journal 側は adapter が同じ値を head と event で確かめる
                if (locked.journalRevision() != proof.binding().expectedHoldRevision()
                        || !Objects.equals(locked.journalEventHash(), hold.journalEventHash())) {
                    throw BusinessException.of(409, "error.privacy.holdReleaseStale");
                }
                if (dependsOnSubjectHolds(locked, catalog) && store.activeSubjectHoldExists(tenantId)) {
                    throw BusinessException.of(409, "error.privacy.holdReleaseDependency");
                }
                String element = locked.targetType() == PrivacyHoldTargetType.DATA_ELEMENT ? locked.dataElementId() : null;
                audit.record(actor.username(), PrivacyHoldAudit.RELEASE_REQUESTED, PrivacyHoldAudit.fields(
                        "hold", locked.holdId(), "target", locked.targetType().name(), "element", element,
                        "request", proof.binding().digest().substring(0, 16),
                        "issuer", validated.issuer().authorityId(), "approver", validated.approver().authorityId()),
                        202, true);
                audit.record(actor.username(), PrivacyHoldAudit.RELEASE_REASON, PrivacyHoldAudit.fields(
                        "hold", locked.holdId(), "reason", validated.reasonCode(), "ticket", validated.ticketRef()),
                        202, true);
                audit.record(actor.username(), PrivacyHoldAudit.RELEASE_DECISIONS, PrivacyHoldAudit.fields(
                        "hold", locked.holdId(), "d03", readiness.d03DecisionId(), "d05", readiness.d05DecisionId()),
                        202, true);
                // pending_since は lock・確認・監査の後、CAS の直前の時刻（hold 開始と同じ順序。P2-N1）
                Instant pendingSince = clock.instant();
                int updated = resend ? store.refreshPending(tenantId, journalId, commandId, pendingSince)
                        : store.recordPending(tenantId, journalId, commandId, pendingSince);
                if (updated != 1) {
                    throw BusinessException.of(409, "error.privacy.holdReleaseInProgress");
                }
            });
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("[privacy-hold] hold解除の予約を記録できません: exceptionClass={}", e.getClass().getSimpleName());
            throw BusinessException.of(503, "error.privacy.sourceUnavailable");
        }
    }

    /**
     * DATA_ELEMENT hold の解除が SUBJECT hold の選択に依存し得るか。SUBJECT hold の開始では、帰属できない element ごとに
     * 「DATA_ELEMENT hold を追加」を選ぶと、その element の ACTIVE な DATA_ELEMENT hold を前提にする（C-2）。どの SUBJECT hold が
     * どの選択をしたかは projection に無い（Journal の request hash と監査だけにある）ため、帰属できない element の
     * DATA_ELEMENT hold は、tenant に ACTIVE な SUBJECT hold がある間は解除しない（判定できない依存は fail-closed）。
     */
    private static boolean dependsOnSubjectHolds(PrivacyHoldProjectionStore.HoldRow hold,
                                                 Map<String, SubjectAttribution> catalog) {
        return hold.targetType() == PrivacyHoldTargetType.DATA_ELEMENT
                && (catalog == null || HoldSubjectResolver.attributionOf(catalog, hold.dataElementId())
                != SubjectAttribution.ATTRIBUTABLE);
    }

    /** append し、ACK 不明なら {@code pending_since} を進めてから同じ proof で1回だけ再送する（hold 開始と同じ規則）。 */
    private JournalAck appendWithRefreshedReplay(HoldReleaseJournalPort port, VerifiedHoldRelease proof, String tenantId,
                                                 String commandId) {
        try {
            return port.appendHoldRelease(proof);
        } catch (JournalConflictException | SecurityException e) {
            throw e;
        } catch (RuntimeException firstUnknown) {
            if (!refreshPendingSince(tenantId, commandId)) {
                throw firstUnknown;
            }
            return port.appendHoldRelease(proof);
        }
    }

    private boolean refreshPendingSince(String tenantId, String commandId) {
        String journalId = journal.journalId();
        try {
            Integer updated = requiresNew.execute(status -> {
                PrivacyHoldProjectionStore.StateRow state = store.lockState(tenantId, journalId).orElse(null);
                if (state == null || !commandId.equals(state.pendingCommandId())) {
                    return 0;
                }
                return store.refreshPending(tenantId, journalId, commandId, clock.instant());
            });
            return updated != null && updated == 1;
        } catch (RuntimeException e) {
            log.warn("[privacy-hold] 解除の再送前にpendingの時刻を進められないため再送しません: exceptionClass={}",
                    e.getClass().getSimpleName());
            return false;
        }
    }

    private void releasePending(String tenantId, String holdId, String commandId, PrivacyHoldService.Actor actor,
                                String reason, int status) {
        log.warn("[privacy-hold] hold解除を拒否しました: code={} reason={}", LOG_CODE_REJECTED, reason);
        try {
            requiresNew.executeWithoutResult(tx -> {
                store.clearPending(tenantId, journal.journalId(), commandId);
                audit.record(actor.username(), PrivacyHoldAudit.RELEASE_REJECTED,
                        PrivacyHoldAudit.fields("hold", holdId, "reason", reason), status, false);
            });
        } catch (RuntimeException e) {
            // pending が残っても reconciler が「Journal に command が無い」ことを確かめてから消す
            log.warn("[privacy-hold] 拒否された解除のpendingを戻せません: exceptionClass={}", e.getClass().getSimpleName());
        }
    }

    private void rejected(PrivacyHoldService.Actor actor, String holdId, String reason, int status) {
        log.warn("[privacy-hold] hold解除を拒否しました: code={} reason={}", LOG_CODE_REJECTED, reason);
        try {
            requiresNew.executeWithoutResult(tx -> audit.record(actor.username(), PrivacyHoldAudit.RELEASE_REJECTED,
                    PrivacyHoldAudit.fields("hold", holdId, "reason", reason), status, false));
        } catch (RuntimeException e) {
            log.warn("[privacy-hold] 解除の拒否の監査を記録できません: exceptionClass={}", e.getClass().getSimpleName());
        }
    }

    private void ackUnknown(String holdId, PrivacyHoldService.Actor actor) {
        log.warn("[privacy-hold] hold解除のACKを確認できません。pendingを残してreconcilerに任せます: code={}",
                LOG_CODE_ACK_UNKNOWN);
        try {
            requiresNew.executeWithoutResult(tx -> audit.record(actor.username(), PrivacyHoldAudit.RELEASE_ACK_UNKNOWN,
                    PrivacyHoldAudit.fields("hold", holdId), 503, false));
        } catch (RuntimeException e) {
            log.warn("[privacy-hold] 解除のACK不明の監査を記録できません: exceptionClass={}", e.getClass().getSimpleName());
        }
    }

    private static Map<String, String> releasedFields(PrivacyHoldProjectionStore.HoldRow hold, JournalEvent event,
                                                      boolean replayed) {
        return PrivacyHoldAudit.fields("hold", hold.holdId(), "target", hold.targetType().name(),
                "element", hold.targetType() == PrivacyHoldTargetType.DATA_ELEMENT ? hold.dataElementId() : null,
                "revision", Long.toString(event.revision()), "event", event.eventHash().substring(0, 16),
                "replayed", replayed ? "true" : null);
    }

    /** 拒否理由の HTTP 応答（理由の詳細は監査と log の固定 code に残す）。 */
    static BusinessException rejection(HoldReleaseRejection reason) {
        return switch (reason) {
            case HOLD_REVISION_MISMATCH, JOURNAL_HEAD_MISMATCH -> BusinessException.of(409, "error.privacy.holdReleaseStale");
            case SEPARATION_OF_DUTIES -> BusinessException.of(403, "error.privacy.holdReleaseSeparation");
            default -> BusinessException.of(403, "error.privacy.holdReleaseProofRejected");
        };
    }

    private Validated validate(String holdId, PrivacyHoldReleaseRequest request) {
        if (holdId == null || !PrivacyHoldPartitions.HOLD_ID.matcher(holdId).matches() || request == null) {
            throw invalid();
        }
        String reasonCode = request.reasonCode();
        if (reasonCode == null || !REASON_CODE.matcher(reasonCode).matches()
                || PrivacyHoldService.RECONCILED.equals(reasonCode)) {
            throw invalid();
        }
        String ticketRef = request.ticketRef();
        if (ticketRef == null || !TICKET_REF.matcher(ticketRef).matches()
                || PrivacyHoldService.RECONCILED.equals(ticketRef)) {
            throw invalid();
        }
        String reasonText = request.reasonText();
        if (!StringUtils.hasText(reasonText) || reasonText.length() > PrivacyHoldService.MAX_REASON_TEXT_LENGTH) {
            throw invalid();
        }
        PrivacyHoldReleaseRequest.Binding binding = request.binding();
        if (binding == null || binding.commandId() == null
                || !PrivacyHoldPartitions.RELEASE_COMMAND_ID.matcher(binding.commandId()).matches()
                || binding.expectedHoldRevision() == null || binding.expectedJournalRevision() == null) {
            throw invalid();
        }
        HoldReleaseBinding presented;
        try {
            presented = new HoldReleaseBinding(binding.tenantId(), binding.subjectRef(), binding.holdId(),
                    binding.expectedHoldRevision(), binding.reasonHash(), binding.commandId(),
                    binding.expectedJournalRevision(), binding.expectedJournalHash(), binding.requesterRef());
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
        return new Validated(presented, claim(request.issuer(), HoldReleaseAuthorityClaim.Role.ISSUER),
                claim(request.approver(), HoldReleaseAuthorityClaim.Role.APPROVER), reasonCode,
                PrivacyHoldService.sha256(reasonText), ticketRef);
    }

    private static HoldReleaseAuthorityClaim claim(PrivacyHoldReleaseRequest.Claim claim,
                                                   HoldReleaseAuthorityClaim.Role role) {
        if (claim == null || claim.authorityId() == null || !AUTHORITY.matcher(claim.authorityId()).matches()
                || claim.keyId() == null || !AUTHORITY.matcher(claim.keyId()).matches() || claim.issuedAt() == null
                || claim.expiresAt() == null || claim.signature() == null
                || claim.signature().length() > MAX_SIGNATURE_LENGTH) {
            throw invalid();
        }
        try {
            return new HoldReleaseAuthorityClaim(role, claim.authorityId(), claim.keyId(), Instant.parse(claim.issuedAt()),
                    Instant.parse(claim.expiresAt()), Base64.getDecoder().decode(claim.signature()));
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw invalid();
        }
    }

    private static BusinessException invalid() {
        return BusinessException.of(400, "error.privacy.holdReleaseInvalidRequest");
    }

    private boolean anchorsConfigured() {
        try {
            return registry != null && registry.anchors() != null && !registry.anchors().isEmpty();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 照会者の tenant（principal に束縛された値）。権限評価の設定 tenant と一致しなければ403。 */
    private String requireCallerTenant(PrivacyHoldService.Actor actor) {
        String callerTenant = actor == null ? null : actor.tenantId();
        String authorizationTenant = oidcProperties != null && StringUtils.hasText(oidcProperties.getTenantId())
                ? oidcProperties.getTenantId() : "default";
        if (!StringUtils.hasText(callerTenant) || !authorizationTenant.equals(callerTenant)) {
            throw BusinessException.of(403, "error.accessDenied");
        }
        return callerTenant;
    }

    /** 検査済みの要求。 */
    record Validated(HoldReleaseBinding presented, HoldReleaseAuthorityClaim issuer, HoldReleaseAuthorityClaim approver,
                     String reasonCode, String reasonHash, String ticketRef) {
    }
}
