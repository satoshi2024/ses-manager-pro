package com.ses.service.repair;

import org.springframework.dao.OptimisticLockingFailureException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 境界A 重試発見および全不変条件検証コンポーネント (Retry Discovery & Invariants Verification)
 * <p>
 * データベース行および承認証跡の不変スナップショット群を受領し、純粋計算として入力整合性・集約バインディング・
 * 重試発見・集合再構成・全不変条件検証を実施する。
 * </p>
 * <p>
 * <strong>アーキテクチャ境界の限定事項:</strong><br>
 * 本コンポーネントはDBアクセス権限や実決裁権限を持たず、渡された入力スナップショット間の一貫性のみを検証する。
 * 漏れのない完全な集約スナップショットのロード、InnoDB行ロック（FOR UPDATE）の取得、および本番における正本決裁承認の成立は、
 * 後続の制御されたローダーおよび承認境界（Qualification / Approval Engine）によって担保される。
 * </p>
 */
public class RepairRetryDiscoveryService {

    private final RepairEvidenceCodec codec;

    public RepairRetryDiscoveryService(RepairEvidenceCodec codec) {
        this.codec = Objects.requireNonNull(codec, "codec must not be null");
    }

    public RepairRetryDiscoveryService() {
        this(new RepairEvidenceCodec());
    }

    /**
     * 集約スナップショット群から、本決裁チケットの完了証跡載体を探索・再構成し、全不変条件を検証する。
     *
     * @param aggregateQueues 集約内の全修復キュー不変スナップショット（親キューおよび全子イベントキュー）
     * @param parentInvoice 親インボイス不変スナップショット
     * @param childEvents 子イベント不変スナップショット一覧（空リスト許容、null不可）
     * @param targetScope コマンド修復ターゲットスコープ
     * @param approvalEvidence 決裁承認証跡スナップショット
     * @return 重試発見結果
     */
    public RetryDiscoveryResult discoverAndVerifyRetry(
        List<ScopeRepairQueueSnapshot> aggregateQueues,
        ParentInvoiceSnapshot parentInvoice,
        List<ChildInvoiceEventSnapshot> childEvents,
        RepairTargetScope targetScope,
        ApprovalEvidenceSnapshot approvalEvidence
    ) {
        // 0. 基本 NULL 検証
        Objects.requireNonNull(aggregateQueues, "aggregateQueues must not be null");
        Objects.requireNonNull(parentInvoice, "parentInvoice must not be null");
        Objects.requireNonNull(childEvents, "childEvents must not be null");
        Objects.requireNonNull(targetScope, "targetScope must not be null");
        Objects.requireNonNull(approvalEvidence, "approvalEvidence must not be null");

        // 1. 入力および集約バインディングの整合性検証
        // (1) 親インボイス ID と修復ターゲットの一致
        if (!Objects.equals(parentInvoice.id(), targetScope.invoiceId())) {
            throw new IllegalArgumentException("親インボイスIDが修復ターゲットと一致しません: expected="
                + targetScope.invoiceId() + ", actual=" + parentInvoice.id());
        }

        // (2) 承認証跡の各属性と修復ターゲットの一致
        if (!Objects.equals(approvalEvidence.invoiceId(), targetScope.invoiceId())) {
            throw new IllegalArgumentException("決裁承認証跡のインボイスIDが修復ターゲットと一致しません: expected="
                + targetScope.invoiceId() + ", actual=" + approvalEvidence.invoiceId());
        }
        if (!Objects.equals(approvalEvidence.targetTenantId(), targetScope.targetTenantId())) {
            throw new SecurityException("決裁承認証跡のテナントスコープが修復ターゲットと一致しません: expected="
                + targetScope.targetTenantId() + ", actual=" + approvalEvidence.targetTenantId());
        }
        if (!Objects.equals(approvalEvidence.targetLegalEntityId(), targetScope.targetLegalEntityId())) {
            throw new SecurityException("決裁承認証跡の法人スコープが修復ターゲットと一致しません: expected="
                + targetScope.targetLegalEntityId() + ", actual=" + approvalEvidence.targetLegalEntityId());
        }

        // (3) 子イベントの重複排除および親インボイス所属検証
        Set<Long> seenEventIds = new HashSet<>();
        for (ChildInvoiceEventSnapshot event : childEvents) {
            Objects.requireNonNull(event, "childEvent must not be null");
            if (!seenEventIds.add(event.id())) {
                throw new IllegalArgumentException("重複する子イベントIDが検知されました: eventId=" + event.id());
            }
            if (!Objects.equals(event.digitalInvoiceId(), targetScope.invoiceId())) {
                throw new IllegalArgumentException("子イベントが親インボイスに属していません: eventId="
                    + event.id() + ", invoiceId=" + event.digitalInvoiceId());
            }
        }

        // (4) 修復キューの重複排除・エンティティ対応・状態検証・プロトコル事前スキャン
        Set<Long> seenQueueIds = new HashSet<>();
        Set<String> seenEntityKeys = new HashSet<>();
        for (ScopeRepairQueueSnapshot q : aggregateQueues) {
            Objects.requireNonNull(q, "queue snapshot must not be null");
            // (a) キューIDの重複検証
            if (!seenQueueIds.add(q.id())) {
                throw new IllegalArgumentException("重複する修復キューIDが検知されました: id=" + q.id());
            }

            // (b) エンティティ種別・IDの対応検証
            if ("DIGITAL_INVOICE".equals(q.entityType())) {
                if (!Objects.equals(q.entityId(), targetScope.invoiceId())) {
                    throw new IllegalArgumentException("親キューのエンティティIDが対象インボイスIDと一致しません: queueId="
                        + q.id() + ", entityId=" + q.entityId());
                }
            } else if ("DIGITAL_INVOICE_EVENT".equals(q.entityType())) {
                if (!seenEventIds.contains(q.entityId())) {
                    throw new IllegalArgumentException("子キューのエンティティIDが入力子イベント一覧に存在しません: queueId="
                        + q.id() + ", entityId=" + q.entityId());
                }
            } else {
                throw new IllegalArgumentException("修復キューのエンティティ種別が不正です: queueId="
                    + q.id() + ", entityType=" + q.entityType());
            }

            // (c) 同一エンティティに対する重複キュー行の排除
            String entityKey = q.entityType() + ":" + q.entityId();
            if (!seenEntityKeys.add(entityKey)) {
                throw new IllegalArgumentException("同一エンティティに対する重複キュー行が検知されました: "
                    + entityKey + ", queueId=" + q.id());
            }

            // (d) キュー状態の検証（PENDING または RESOLVED 以外は未知状態として即時拒絶）
            if (!"PENDING".equals(q.status()) && !"RESOLVED".equals(q.status())) {
                throw new IllegalStateException("修復キューのステータスが不正です: queueId="
                    + q.id() + ", status=" + q.status());
            }

            // (e) プロトコル標識行の事前構文検証（carrier探索・早期リターンの前に全件検証）
            String note = q.resolutionNote();
            if (note != null) {
                if (note.startsWith(RepairEvidenceCodec.CARRIER_PREFIX)) {
                    // 代表載体標識行のパース検証（構文不正・未知バージョン・欠落時は CorruptedEvidenceException が送出される）
                    codec.parseCarrier(note);
                } else if (note.startsWith(RepairEvidenceCodec.MEMBER_PREFIX)) {
                    // 参加部材標識行のパース検証（破損時は CorruptedEvidenceException が送出される）
                    codec.parseMember(note);
                }
                // それ以外の歴史的普通備考（例: [IDEMPOTENT_CLOSE] 等）は安全に無視・現状維持
            }
        }

        // 2. 代表載体の精確探索
        List<CarrierMatch> matchingCarriers = new ArrayList<>();
        for (ScopeRepairQueueSnapshot q : aggregateQueues) {
            if (!"RESOLVED".equals(q.status())) {
                continue;
            }
            CarrierEvidence carrier = codec.parseCarrier(q.resolutionNote());
            if (carrier == null) {
                continue;
            }
            if (carrier.ticket().equals(approvalEvidence.requestNo())
                && carrier.round() == approvalEvidence.roundNo()
                && carrier.invId() == targetScope.invoiceId()) {
                matchingCarriers.add(new CarrierMatch(q, carrier));
            }
        }

        if (matchingCarriers.isEmpty()) {
            // 本チケット・輪次による完了証跡不在（初回実行または未結案経路）
            return RetryDiscoveryResult.notResolved();
        }

        if (matchingCarriers.size() > 1) {
            throw new IllegalStateException("同一決裁チケットの完了証跡載体が複数検知されました: count=" + matchingCarriers.size());
        }

        CarrierMatch match = matchingCarriers.get(0);
        ScopeRepairQueueSnapshot carrierQueue = match.queue();
        CarrierEvidence carrier = match.evidence();

        // 3. 部材キューの収集および参加集合の再構成
        List<ScopeRepairQueueSnapshot> members = new ArrayList<>();
        for (ScopeRepairQueueSnapshot q : aggregateQueues) {
            if (q.id().equals(carrierQueue.id())) {
                continue;
            }
            if (!"RESOLVED".equals(q.status())) {
                continue;
            }
            MemberEvidence member = codec.parseMember(q.resolutionNote());
            if (member == null) {
                continue;
            }
            if (member.ticket().equals(approvalEvidence.requestNo()) && member.round() == approvalEvidence.roundNo()) {
                if (member.carrierId() != carrierQueue.id()) {
                    throw new LinkageConflictException("部材レコードが異なる載体IDを指定しています: expected="
                        + carrierQueue.id() + ", actual=" + member.carrierId() + ", queueId=" + q.id());
                }
                members.add(q);
            }
        }

        List<ScopeRepairQueueSnapshot> reconstructed = new ArrayList<>();
        reconstructed.add(carrierQueue);
        reconstructed.addAll(members);

        // 4. 暗号学的ハッシュおよび件数の厳格検証
        if (reconstructed.size() != carrier.qc()) {
            throw new CorruptedEvidenceException("再構成された参加キュー件数が載体の記録と不一致です: expected="
                + carrier.qc() + ", actual=" + reconstructed.size());
        }

        List<Long> reconstructedIds = reconstructed.stream().map(ScopeRepairQueueSnapshot::id).toList();
        String calculatedHash = codec.calculateQueueDigest(reconstructedIds);
        if (!calculatedHash.equals(carrier.qh())) {
            throw new CorruptedEvidenceException("再構成された参加キューのハッシュ値が載体の記録と不一致です: expected="
                + carrier.qh() + ", actual=" + calculatedHash);
        }

        // 5. 重試時全不変条件の復帰検証
        // (1) テナントスコープ一致
        if (!carrier.tenant().equals(targetScope.targetTenantId())) {
            throw new SecurityException("載体に記録されたテナントスコープがコマンド対象と一致しません: expected="
                + targetScope.targetTenantId() + ", actual=" + carrier.tenant());
        }

        // (2) 法人スコープ一致
        if (!Objects.equals(carrier.legalEntity(), targetScope.targetLegalEntityId())) {
            throw new SecurityException("載体に記録された法人スコープがコマンド対象と一致しません: expected="
                + targetScope.targetLegalEntityId() + ", actual=" + carrier.legalEntity());
        }

        // (3) 承認正本バージョン合致
        if (carrier.vBf() != approvalEvidence.targetVersion()) {
            throw new OptimisticLockingFailureException("載体の修復前版が決裁承認対象バージョンと一致しません: approval="
                + approvalEvidence.targetVersion() + ", carrier=" + carrier.vBf());
        }

        // (4) 分類別バージョン整合性
        if ("P1".equals(carrier.pCls())) {
            if (carrier.vAf() != carrier.vBf()) {
                throw new IllegalStateException("P1 載体の修復後バージョンが修復前バージョンと一致しません: vAf="
                    + carrier.vAf() + ", vBf=" + carrier.vBf());
            }
        } else if ("P2".equals(carrier.pCls())) {
            if (carrier.vAf() != carrier.vBf() + 1) {
                throw new IllegalStateException("P2 載体の修復後バージョンが修復前バージョン+1と一致しません: vAf="
                    + carrier.vAf() + ", vBf=" + carrier.vBf());
            }
        } else {
            throw new IllegalStateException("未知の修復分類です: " + carrier.pCls());
        }

        // (5) 親行 DB 現バージョン一致
        if (!Objects.equals(parentInvoice.version(), carrier.vAf())) {
            throw new OptimisticLockingFailureException("親インボイスの現在バージョンが載体修復後バージョンと一致しません: db="
                + parentInvoice.version() + ", carrier=" + carrier.vAf());
        }

        // (6) DB 現行スコープ一致
        if (!Objects.equals(parentInvoice.tenantId(), targetScope.targetTenantId())
            || !Objects.equals(parentInvoice.legalEntityId(), targetScope.targetLegalEntityId())) {
            throw new SecurityException("親インボイスのDB現在スコープが修復ターゲットと一致しません");
        }
        for (ChildInvoiceEventSnapshot event : childEvents) {
            if (!Objects.equals(event.tenantId(), targetScope.targetTenantId())
                || !Objects.equals(event.legalEntityId(), targetScope.targetLegalEntityId())) {
                throw new SecurityException("子イベントのDB現在スコープが修復ターゲットと一致しません: eventId=" + event.id());
            }
        }

        return RetryDiscoveryResult.alreadyResolved(carrier, carrierQueue, reconstructed);
    }

    private record CarrierMatch(ScopeRepairQueueSnapshot queue, CarrierEvidence evidence) {}
}
