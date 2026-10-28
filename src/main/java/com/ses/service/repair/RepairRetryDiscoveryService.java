package com.ses.service.repair;

import org.springframework.dao.OptimisticLockingFailureException;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 境界A 重試発見および全不変条件検証コンポーネント (Retry Discovery & Invariants Verification)
 * <p>
 * 入力としてデータベース行の不変スナップショット群を受領し、純粋計算として重試発見・集合再構成・
 * 全不変条件検証を実施する。
 * 本コンポーネントはDB接続や生産実行権限を持たず、副作用を生じない。
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
     * ロック取得済みの集約スナップショット群から、本決裁チケットの完了証跡載体を探索・再構成し、全不変条件を検証する。
     *
     * @param aggregateQueues 集約内の全修復キュー不変スナップショット（親キューおよび全子イベントキュー）
     * @param parentInvoice 親インボイス不変スナップショット
     * @param childEvents 子イベント不変スナップショット一覧
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
        Objects.requireNonNull(aggregateQueues, "aggregateQueues must not be null");
        Objects.requireNonNull(parentInvoice, "parentInvoice must not be null");
        Objects.requireNonNull(targetScope, "targetScope must not be null");
        Objects.requireNonNull(approvalEvidence, "approvalEvidence must not be null");

        // 1. 代表載体の精確探索
        List<CarrierMatch> matchingCarriers = new ArrayList<>();
        for (ScopeRepairQueueSnapshot q : aggregateQueues) {
            if (!"RESOLVED".equals(q.status())) {
                continue;
            }
            CarrierEvidence carrier = codec.parseCarrier(q.resolutionNote());
            if (carrier == null) {
                // 歴史的普通備考または部材参照行はスキップ
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

        // 2. 部材キューの収集および参加集合の再構成
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
                // 歴史的普通備考または無関係な行はスキップ
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

        // 3. 暗号学的ハッシュおよび件数の厳格検証
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

        // 4. 重試時全不変条件の復帰検証
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
        if (childEvents != null) {
            for (ChildInvoiceEventSnapshot event : childEvents) {
                if (!Objects.equals(event.tenantId(), targetScope.targetTenantId())
                    || !Objects.equals(event.legalEntityId(), targetScope.targetLegalEntityId())) {
                    throw new SecurityException("子イベントのDB現在スコープが修復ターゲットと一致しません: eventId=" + event.id());
                }
            }
        }

        return RetryDiscoveryResult.alreadyResolved(carrier, carrierQueue, reconstructed);
    }

    private record CarrierMatch(ScopeRepairQueueSnapshot queue, CarrierEvidence evidence) {}
}
