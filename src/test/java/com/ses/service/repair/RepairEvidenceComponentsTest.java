package com.ses.service.repair;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 境界A 修復証跡コンポーネント（コーデック・初回選定・重試発見・不変条件検証）の独立単体テスト。
 * <p>
 * DB 接続を一切行わず、純粋メモリ内不変スナップショットを用いて全境界・エッジケースを実証する。
 * </p>
 */
class RepairEvidenceComponentsTest {

    private RepairEvidenceCodec codec;
    private RepairRetryDiscoveryService discoveryService;

    @BeforeEach
    void setUp() {
        codec = new RepairEvidenceCodec();
        discoveryService = new RepairRetryDiscoveryService(codec);
    }

    // =========================================================================
    // 1. 正常系・エンコード/デコード
    // =========================================================================

    @Test
    @DisplayName("正常系: 代表載体証跡 (SES_NF09_COMPL) のエンコードおよび完全一致パース")
    void testEncodeAndParseCarrier_valid() {
        String digest = codec.calculateQueueDigest(List.of(100L, 101L, 102L));
        CarrierEvidence original = new CarrierEvidence(
            1,
            "APPR-20261026-INV-0089",
            1,
            10089L,
            "tenant-1",
            100L,
            "P2",
            0L,
            1L,
            3,
            digest,
            1L,
            "2026-10-28T16:30:00Z"
        );

        String encoded = codec.encodeCarrier(original);
        assertThat(encoded).startsWith(RepairEvidenceCodec.CARRIER_PREFIX);

        CarrierEvidence parsed = codec.parseCarrier(encoded);
        assertThat(parsed).isNotNull();
        assertThat(parsed.v()).isEqualTo(1);
        assertThat(parsed.ticket()).isEqualTo("APPR-20261026-INV-0089");
        assertThat(parsed.round()).isEqualTo(1);
        assertThat(parsed.invId()).isEqualTo(10089L);
        assertThat(parsed.tenant()).isEqualTo("tenant-1");
        assertThat(parsed.legalEntity()).isEqualTo(100L);
        assertThat(parsed.pCls()).isEqualTo("P2");
        assertThat(parsed.vBf()).isEqualTo(0L);
        assertThat(parsed.vAf()).isEqualTo(1L);
        assertThat(parsed.qc()).isEqualTo(3);
        assertThat(parsed.qh()).isEqualTo(digest);
        assertThat(parsed.by()).isEqualTo(1L);
        assertThat(parsed.at()).isEqualTo("2026-10-28T16:30:00Z");
    }

    @Test
    @DisplayName("正常系: 参加部材参照 (SES_NF09_MEMBER) のエンコードおよび完全一致パース")
    void testEncodeAndParseMember_valid() {
        MemberEvidence original = new MemberEvidence(
            1,
            100L,
            "APPR-20261026-INV-0089",
            1
        );

        String encoded = codec.encodeMember(original);
        assertThat(encoded).startsWith(RepairEvidenceCodec.MEMBER_PREFIX);

        MemberEvidence parsed = codec.parseMember(encoded);
        assertThat(parsed).isNotNull();
        assertThat(parsed.v()).isEqualTo(1);
        assertThat(parsed.carrierId()).isEqualTo(100L);
        assertThat(parsed.ticket()).isEqualTo("APPR-20261026-INV-0089");
        assertThat(parsed.round()).isEqualTo(1);
    }

    // =========================================================================
    // 2. 容量境界 (Capacity Boundary)
    // =========================================================================

    @Test
    @DisplayName("容量境界: 500バイト以内のシリアライズは正常通過すること")
    void testCapacityBoundary_within500BytesPasses() {
        String digest = codec.calculateQueueDigest(List.of(100L));
        // tenant 長を約 80 文字に設定して適正範囲内であることを検証
        String tenant80 = "t".repeat(80);
        CarrierEvidence evidence = new CarrierEvidence(
            1, "APPR-001", 1, 100L, tenant80, 100L, "P1", 0L, 0L, 1, digest, 1L, "2026-10-28T16:30:00Z"
        );

        String encoded = codec.encodeCarrier(evidence);
        assertThat(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
            .isLessThanOrEqualTo(RepairEvidenceCodec.MAX_UTF8_BYTES);
    }

    @Test
    @DisplayName("容量境界: 500バイト超過時は截断せず直ちに IllegalStateException を送出すること")
    void testCapacityBoundary_over500BytesThrows() {
        String digest = codec.calculateQueueDigest(List.of(100L));
        // tenant 長を 350 文字にして 500 バイトを超過させる
        String hugeTenant = "t".repeat(350);
        CarrierEvidence evidence = new CarrierEvidence(
            1, "APPR-001", 1, 100L, hugeTenant, 100L, "P1", 0L, 0L, 1, digest, 1L, "2026-10-28T16:30:00Z"
        );

        assertThatThrownBy(() -> codec.encodeCarrier(evidence))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("500バイトを超過しています");
    }

    // =========================================================================
    // 3. 歴史的普通備考の無視・保護 (Historical Ordinary Notes Ignored)
    // =========================================================================

    @Test
    @DisplayName("旧自由文分離: 非プロトコル行・歴史的自由テキストは例外なく null を返却して安全に無視すること")
    void testParse_historicalNotesIgnored() {
        assertThat(codec.parseCarrier(null)).isNull();
        assertThat(codec.parseCarrier("[IDEMPOTENT_CLOSE]")).isNull();
        assertThat(codec.parseCarrier("Migration note 2026-10-01")).isNull();
        assertThat(codec.parseCarrier("SES_OTHER:some_payload")).isNull();

        assertThat(codec.parseMember(null)).isNull();
        assertThat(codec.parseMember("[IDEMPOTENT_CLOSE]")).isNull();
        assertThat(codec.parseMember("Old legacy member memo")).isNull();
        assertThat(codec.parseMember("SES_OTHER:some_payload")).isNull();
    }

    // =========================================================================
    // 4. プロトコル標識行の破損・未知バージョン・欠落拒絶 (Corrupted Evidence Rejection)
    // =========================================================================

    @Test
    @DisplayName("異常系: プロトコル標識行がJSON不正の場合は CorruptedEvidenceException を送出すること")
    void testParse_corruptedProtocolRecords_syntaxErrorThrows() {
        assertThatThrownBy(() -> codec.parseCarrier("SES_NF09_COMPL:{malformed_json}"))
            .isInstanceOf(CorruptedEvidenceException.class);

        assertThatThrownBy(() -> codec.parseMember("SES_NF09_MEMBER:{malformed_json}"))
            .isInstanceOf(CorruptedEvidenceException.class);
    }

    @Test
    @DisplayName("異常系: プロトコルバージョン不一致 (v != 1) の場合は CorruptedEvidenceException を送出すること")
    void testParse_corruptedProtocolRecords_versionMismatchThrows() {
        String digest = codec.calculateQueueDigest(List.of(100L));
        String jsonV2 = "SES_NF09_COMPL:{\"v\":2,\"ticket\":\"T1\",\"round\":1,\"invId\":100,\"tenant\":\"t1\",\"legalEntity\":100,\"pCls\":\"P1\",\"vBf\":0,\"vAf\":0,\"qc\":1,\"qh\":\""
            + digest + "\",\"by\":1,\"at\":\"2026-10-28T16:30:00Z\"}";

        assertThatThrownBy(() -> codec.parseCarrier(jsonV2))
            .isInstanceOf(CorruptedEvidenceException.class)
            .hasMessageContaining("未対応のプロトコルバージョン");

        String memberJsonV2 = "SES_NF09_MEMBER:{\"v\":2,\"carrierId\":100,\"ticket\":\"T1\",\"round\":1}";
        assertThatThrownBy(() -> codec.parseMember(memberJsonV2))
            .isInstanceOf(CorruptedEvidenceException.class)
            .hasMessageContaining("未対応のプロトコルバージョン");
    }

    @Test
    @DisplayName("異常系: 必須フィールド欠落時は CorruptedEvidenceException を送出すること")
    void testParse_corruptedProtocolRecords_missingFieldThrows() {
        // vBf が欠落した carrier JSON
        String missingVbf = "SES_NF09_COMPL:{\"v\":1,\"ticket\":\"T1\",\"round\":1,\"invId\":100,\"tenant\":\"t1\",\"legalEntity\":100,\"pCls\":\"P1\",\"vAf\":0,\"qc\":1,\"qh\":\"...\",\"by\":1,\"at\":\"...\"}";
        assertThatThrownBy(() -> codec.parseCarrier(missingVbf))
            .isInstanceOf(CorruptedEvidenceException.class)
            .hasMessageContaining("必須フィールドが欠落またはNULL");

        // carrierId が欠落した member JSON
        String missingCarrierId = "SES_NF09_MEMBER:{\"v\":1,\"ticket\":\"T1\",\"round\":1}";
        assertThatThrownBy(() -> codec.parseMember(missingCarrierId))
            .isInstanceOf(CorruptedEvidenceException.class)
            .hasMessageContaining("必須フィールドが欠落またはNULL");
    }

    // =========================================================================
    // 5. 初回実行時 確定載体選定 (Initial Carrier Selection)
    // =========================================================================

    @Test
    @DisplayName("初回選定: 親キューが PENDING の場合は親キューが最優先で載体に選定されること")
    void testInitialCarrierSelection_parentPendingPriority() {
        ScopeRepairQueueSnapshot parent = new ScopeRepairQueueSnapshot(100L, "DIGITAL_INVOICE", 10089L, "PENDING", null, 0L);
        ScopeRepairQueueSnapshot child1 = new ScopeRepairQueueSnapshot(10L, "DIGITAL_INVOICE_EVENT", 2001L, "PENDING", null, 0L);
        ScopeRepairQueueSnapshot child2 = new ScopeRepairQueueSnapshot(20L, "DIGITAL_INVOICE_EVENT", 2002L, "PENDING", null, 0L);

        ScopeRepairQueueSnapshot selected = InitialCarrierSelector.selectInitialCarrier(parent, List.of(parent, child1, child2));
        assertThat(selected.id()).isEqualTo(100L);
    }

    @Test
    @DisplayName("初回選定: 親キューが不在 (NO_QUEUE) または既結案の場合は参加子キュー最小IDが選定されること")
    void testInitialCarrierSelection_parentNotPendingOrNoQueue_selectsMinParticipatingChild() {
        ScopeRepairQueueSnapshot child1 = new ScopeRepairQueueSnapshot(20L, "DIGITAL_INVOICE_EVENT", 2001L, "PENDING", null, 0L);
        ScopeRepairQueueSnapshot child2 = new ScopeRepairQueueSnapshot(10L, "DIGITAL_INVOICE_EVENT", 2002L, "PENDING", null, 0L);

        // 親が null (NO_QUEUE)
        ScopeRepairQueueSnapshot selectedNoQueue = InitialCarrierSelector.selectInitialCarrier(null, List.of(child1, child2));
        assertThat(selectedNoQueue.id()).isEqualTo(10L);

        // 親が既に RESOLVED
        ScopeRepairQueueSnapshot resolvedParent = new ScopeRepairQueueSnapshot(100L, "DIGITAL_INVOICE", 10089L, "RESOLVED", "old-note", 1L);
        ScopeRepairQueueSnapshot selectedResolvedParent = InitialCarrierSelector.selectInitialCarrier(resolvedParent, List.of(child1, child2));
        assertThat(selectedResolvedParent.id()).isEqualTo(10L);
    }

    @Test
    @DisplayName("初回選定: 集約内に旧チケットのより小さいIDの既結案キューが存在しても参加PENDING群の最小が選ばれること")
    void testInitialCarrierSelection_ignoresOldResolvedQueueWithSmallerId() {
        // 旧キュー id=5 は RESOLVED であり、今回の参加キュー (PENDING) には含まれない
        ScopeRepairQueueSnapshot child20 = new ScopeRepairQueueSnapshot(20L, "DIGITAL_INVOICE_EVENT", 2001L, "PENDING", null, 0L);
        ScopeRepairQueueSnapshot child30 = new ScopeRepairQueueSnapshot(30L, "DIGITAL_INVOICE_EVENT", 2002L, "PENDING", null, 0L);

        ScopeRepairQueueSnapshot selected = InitialCarrierSelector.selectInitialCarrier(null, List.of(child20, child30));
        assertThat(selected.id()).isEqualTo(20L);
    }

    // =========================================================================
    // 6. 重試発見・再構成・全不変条件検証 (Retry Discovery & Invariants Verification)
    // =========================================================================

    @Test
    @DisplayName("重試正常系: P-1 (更新なし +0) の重試発見・集合再構成・全不変条件合致で成功判定")
    void testRetryDiscovery_p1Success() {
        long invId = 10089L;
        String ticket = "APPR-20261026-INV-0089";
        int round = 1;
        String tenant = "tenant-1";
        long legalEntity = 100L;

        String digest = codec.calculateQueueDigest(List.of(100L, 101L));
        CarrierEvidence carrierEv = new CarrierEvidence(1, ticket, round, invId, tenant, legalEntity, "P1", 0L, 0L, 2, digest, 1L, "2026-10-28T16:30:00Z");
        MemberEvidence memberEv = new MemberEvidence(1, 100L, ticket, round);

        ScopeRepairQueueSnapshot qCarrier = new ScopeRepairQueueSnapshot(100L, "DIGITAL_INVOICE", invId, "RESOLVED", codec.encodeCarrier(carrierEv), 1L);
        ScopeRepairQueueSnapshot qMember = new ScopeRepairQueueSnapshot(101L, "DIGITAL_INVOICE_EVENT", 2001L, "RESOLVED", codec.encodeMember(memberEv), 1L);

        ParentInvoiceSnapshot parentInvoice = new ParentInvoiceSnapshot(invId, tenant, legalEntity, 0L);
        ChildInvoiceEventSnapshot childEvent = new ChildInvoiceEventSnapshot(2001L, invId, tenant, legalEntity);
        RepairTargetScope targetScope = new RepairTargetScope(invId, tenant, legalEntity, 0L);
        ApprovalEvidenceSnapshot approvalEvidence = new ApprovalEvidenceSnapshot(ticket, round, invId, tenant, legalEntity, 0L);

        RetryDiscoveryResult result = discoveryService.discoverAndVerifyRetry(
            List.of(qCarrier, qMember),
            parentInvoice,
            List.of(childEvent),
            targetScope,
            approvalEvidence
        );

        assertThat(result.alreadyResolved()).isTrue();
        assertThat(result.carrierQueue().id()).isEqualTo(100L);
        assertThat(result.reconstructedQueues()).extracting(ScopeRepairQueueSnapshot::id).containsExactly(100L, 101L);
    }

    @Test
    @DisplayName("重試正常系: P-2 (親CAS更新 +1) の重試発見・集合再構成・全不変条件合致で成功判定")
    void testRetryDiscovery_p2Success() {
        long invId = 10089L;
        String ticket = "APPR-20261026-INV-0089";
        int round = 1;
        String tenant = "tenant-1";
        long legalEntity = 100L;

        String digest = codec.calculateQueueDigest(List.of(100L, 101L, 102L));
        CarrierEvidence carrierEv = new CarrierEvidence(1, ticket, round, invId, tenant, legalEntity, "P2", 0L, 1L, 3, digest, 1L, "2026-10-28T16:30:00Z");
        MemberEvidence memberEv1 = new MemberEvidence(1, 100L, ticket, round);
        MemberEvidence memberEv2 = new MemberEvidence(1, 100L, ticket, round);

        ScopeRepairQueueSnapshot qCarrier = new ScopeRepairQueueSnapshot(100L, "DIGITAL_INVOICE", invId, "RESOLVED", codec.encodeCarrier(carrierEv), 1L);
        ScopeRepairQueueSnapshot qMember1 = new ScopeRepairQueueSnapshot(101L, "DIGITAL_INVOICE_EVENT", 2001L, "RESOLVED", codec.encodeMember(memberEv1), 1L);
        ScopeRepairQueueSnapshot qMember2 = new ScopeRepairQueueSnapshot(102L, "DIGITAL_INVOICE_EVENT", 2002L, "RESOLVED", codec.encodeMember(memberEv2), 1L);

        ParentInvoiceSnapshot parentInvoice = new ParentInvoiceSnapshot(invId, tenant, legalEntity, 1L);
        ChildInvoiceEventSnapshot child1 = new ChildInvoiceEventSnapshot(2001L, invId, tenant, legalEntity);
        ChildInvoiceEventSnapshot child2 = new ChildInvoiceEventSnapshot(2002L, invId, tenant, legalEntity);
        RepairTargetScope targetScope = new RepairTargetScope(invId, tenant, legalEntity, 0L);
        ApprovalEvidenceSnapshot approvalEvidence = new ApprovalEvidenceSnapshot(ticket, round, invId, tenant, legalEntity, 0L);

        RetryDiscoveryResult result = discoveryService.discoverAndVerifyRetry(
            List.of(qCarrier, qMember1, qMember2),
            parentInvoice,
            List.of(child1, child2),
            targetScope,
            approvalEvidence
        );

        assertThat(result.alreadyResolved()).isTrue();
        assertThat(result.carrierQueue().id()).isEqualTo(100L);
        assertThat(result.reconstructedQueues()).hasSize(3);
    }

    @Test
    @DisplayName("重試正常系: 本チケットの載体不在時は alreadyResolved=false を返却して通常実行経路へ進むこと")
    void testRetryDiscovery_carrierNotFound() {
        ScopeRepairQueueSnapshot q1 = new ScopeRepairQueueSnapshot(100L, "DIGITAL_INVOICE", 10089L, "PENDING", null, 0L);
        ParentInvoiceSnapshot parentInvoice = new ParentInvoiceSnapshot(10089L, "tenant-1", 100L, 0L);
        RepairTargetScope targetScope = new RepairTargetScope(10089L, "tenant-1", 100L, 0L);
        ApprovalEvidenceSnapshot approvalEvidence = new ApprovalEvidenceSnapshot("APPR-001", 1, 10089L, "tenant-1", 100L, 0L);

        RetryDiscoveryResult result = discoveryService.discoverAndVerifyRetry(
            List.of(q1), parentInvoice, List.of(), targetScope, approvalEvidence
        );

        assertThat(result.alreadyResolved()).isFalse();
    }

    @Test
    @DisplayName("重試異常系: 同一集約内に同一チケットの載体が複数存在する場合は IllegalStateException を送出すること")
    void testRetryDiscovery_duplicateCarriersRejection() {
        long invId = 10089L;
        String ticket = "APPR-001";
        int round = 1;
        String digest = codec.calculateQueueDigest(List.of(100L));
        CarrierEvidence carrierEv = new CarrierEvidence(1, ticket, round, invId, "tenant-1", 100L, "P1", 0L, 0L, 1, digest, 1L, "2026-10-28T16:30:00Z");

        ScopeRepairQueueSnapshot qCarrier1 = new ScopeRepairQueueSnapshot(100L, "DIGITAL_INVOICE", invId, "RESOLVED", codec.encodeCarrier(carrierEv), 1L);
        ScopeRepairQueueSnapshot qCarrier2 = new ScopeRepairQueueSnapshot(101L, "DIGITAL_INVOICE_EVENT", 2001L, "RESOLVED", codec.encodeCarrier(carrierEv), 1L);

        ParentInvoiceSnapshot parentInvoice = new ParentInvoiceSnapshot(invId, "tenant-1", 100L, 0L);
        RepairTargetScope targetScope = new RepairTargetScope(invId, "tenant-1", 100L, 0L);
        ApprovalEvidenceSnapshot approvalEvidence = new ApprovalEvidenceSnapshot(ticket, round, invId, "tenant-1", 100L, 0L);

        assertThatThrownBy(() -> discoveryService.discoverAndVerifyRetry(
            List.of(qCarrier1, qCarrier2), parentInvoice, List.of(), targetScope, approvalEvidence
        )).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("同一決裁チケットの完了証跡載体が複数検知されました");
    }

    @Test
    @DisplayName("重試異常系: 部材キューが異なる載体IDを指定している場合は LinkageConflictException を送出すること")
    void testRetryDiscovery_memberLinkageConflictRejection() {
        long invId = 10089L;
        String ticket = "APPR-001";
        int round = 1;
        String digest = codec.calculateQueueDigest(List.of(100L, 101L));
        CarrierEvidence carrierEv = new CarrierEvidence(1, ticket, round, invId, "tenant-1", 100L, "P1", 0L, 0L, 2, digest, 1L, "2026-10-28T16:30:00Z");
        // 部材が carrierId=999 を指定（本来は 100L）
        MemberEvidence conflictMember = new MemberEvidence(1, 999L, ticket, round);

        ScopeRepairQueueSnapshot qCarrier = new ScopeRepairQueueSnapshot(100L, "DIGITAL_INVOICE", invId, "RESOLVED", codec.encodeCarrier(carrierEv), 1L);
        ScopeRepairQueueSnapshot qMember = new ScopeRepairQueueSnapshot(101L, "DIGITAL_INVOICE_EVENT", 2001L, "RESOLVED", codec.encodeMember(conflictMember), 1L);

        ParentInvoiceSnapshot parentInvoice = new ParentInvoiceSnapshot(invId, "tenant-1", 100L, 0L);
        RepairTargetScope targetScope = new RepairTargetScope(invId, "tenant-1", 100L, 0L);
        ApprovalEvidenceSnapshot approvalEvidence = new ApprovalEvidenceSnapshot(ticket, round, invId, "tenant-1", 100L, 0L);

        assertThatThrownBy(() -> discoveryService.discoverAndVerifyRetry(
            List.of(qCarrier, qMember), parentInvoice, List.of(), targetScope, approvalEvidence
        )).isInstanceOf(LinkageConflictException.class)
          .hasMessageContaining("異なる載体IDを指定しています");
    }

    @Test
    @DisplayName("重試異常系: 部材欠落 (qc 不一致) の場合は CorruptedEvidenceException を送出すること")
    void testRetryDiscovery_memberCountMismatchRejection() {
        long invId = 10089L;
        String ticket = "APPR-001";
        int round = 1;
        String digest = codec.calculateQueueDigest(List.of(100L, 101L, 102L));
        // carrier は qc=3 を主張しているが部材が 1 件欠落して再構成が 2 件しかない
        CarrierEvidence carrierEv = new CarrierEvidence(1, ticket, round, invId, "tenant-1", 100L, "P1", 0L, 0L, 3, digest, 1L, "2026-10-28T16:30:00Z");
        MemberEvidence memberEv = new MemberEvidence(1, 100L, ticket, round);

        ScopeRepairQueueSnapshot qCarrier = new ScopeRepairQueueSnapshot(100L, "DIGITAL_INVOICE", invId, "RESOLVED", codec.encodeCarrier(carrierEv), 1L);
        ScopeRepairQueueSnapshot qMember = new ScopeRepairQueueSnapshot(101L, "DIGITAL_INVOICE_EVENT", 2001L, "RESOLVED", codec.encodeMember(memberEv), 1L);

        ParentInvoiceSnapshot parentInvoice = new ParentInvoiceSnapshot(invId, "tenant-1", 100L, 0L);
        RepairTargetScope targetScope = new RepairTargetScope(invId, "tenant-1", 100L, 0L);
        ApprovalEvidenceSnapshot approvalEvidence = new ApprovalEvidenceSnapshot(ticket, round, invId, "tenant-1", 100L, 0L);

        assertThatThrownBy(() -> discoveryService.discoverAndVerifyRetry(
            List.of(qCarrier, qMember), parentInvoice, List.of(), targetScope, approvalEvidence
        )).isInstanceOf(CorruptedEvidenceException.class)
          .hasMessageContaining("件数が載体の記録と不一致");
    }

    @Test
    @DisplayName("重試異常系: 暗号学的ハッシュ不一致 (qh 不一致) の場合は CorruptedEvidenceException を送出すること")
    void testRetryDiscovery_hashMismatchRejection() {
        long invId = 10089L;
        String ticket = "APPR-001";
        int round = 1;
        // 意図的に別のキューID集合のダイジェストを指定
        String wrongDigest = codec.calculateQueueDigest(List.of(999L));
        CarrierEvidence carrierEv = new CarrierEvidence(1, ticket, round, invId, "tenant-1", 100L, "P1", 0L, 0L, 1, wrongDigest, 1L, "2026-10-28T16:30:00Z");

        ScopeRepairQueueSnapshot qCarrier = new ScopeRepairQueueSnapshot(100L, "DIGITAL_INVOICE", invId, "RESOLVED", codec.encodeCarrier(carrierEv), 1L);

        ParentInvoiceSnapshot parentInvoice = new ParentInvoiceSnapshot(invId, "tenant-1", 100L, 0L);
        RepairTargetScope targetScope = new RepairTargetScope(invId, "tenant-1", 100L, 0L);
        ApprovalEvidenceSnapshot approvalEvidence = new ApprovalEvidenceSnapshot(ticket, round, invId, "tenant-1", 100L, 0L);

        assertThatThrownBy(() -> discoveryService.discoverAndVerifyRetry(
            List.of(qCarrier), parentInvoice, List.of(), targetScope, approvalEvidence
        )).isInstanceOf(CorruptedEvidenceException.class)
          .hasMessageContaining("ハッシュ値が載体の記録と不一致");
    }

    @Test
    @DisplayName("重試異常系: 載体テナント不一致の場合は SecurityException を送出すること")
    void testRetryDiscovery_tenantMismatchRejection() {
        long invId = 10089L;
        String ticket = "APPR-001";
        int round = 1;
        String digest = codec.calculateQueueDigest(List.of(100L));
        CarrierEvidence carrierEv = new CarrierEvidence(1, ticket, round, invId, "tenant-other", 100L, "P1", 0L, 0L, 1, digest, 1L, "2026-10-28T16:30:00Z");

        ScopeRepairQueueSnapshot qCarrier = new ScopeRepairQueueSnapshot(100L, "DIGITAL_INVOICE", invId, "RESOLVED", codec.encodeCarrier(carrierEv), 1L);
        ParentInvoiceSnapshot parentInvoice = new ParentInvoiceSnapshot(invId, "tenant-1", 100L, 0L);
        RepairTargetScope targetScope = new RepairTargetScope(invId, "tenant-1", 100L, 0L);
        ApprovalEvidenceSnapshot approvalEvidence = new ApprovalEvidenceSnapshot(ticket, round, invId, "tenant-1", 100L, 0L);

        assertThatThrownBy(() -> discoveryService.discoverAndVerifyRetry(
            List.of(qCarrier), parentInvoice, List.of(), targetScope, approvalEvidence
        )).isInstanceOf(SecurityException.class)
          .hasMessageContaining("テナントスコープがコマンド対象と一致しません");
    }

    @Test
    @DisplayName("重試異常系: 載体法人不一致の場合は SecurityException を送出すること")
    void testRetryDiscovery_legalEntityMismatchRejection() {
        long invId = 10089L;
        String ticket = "APPR-001";
        int round = 1;
        String digest = codec.calculateQueueDigest(List.of(100L));
        CarrierEvidence carrierEv = new CarrierEvidence(1, ticket, round, invId, "tenant-1", 999L, "P1", 0L, 0L, 1, digest, 1L, "2026-10-28T16:30:00Z");

        ScopeRepairQueueSnapshot qCarrier = new ScopeRepairQueueSnapshot(100L, "DIGITAL_INVOICE", invId, "RESOLVED", codec.encodeCarrier(carrierEv), 1L);
        ParentInvoiceSnapshot parentInvoice = new ParentInvoiceSnapshot(invId, "tenant-1", 100L, 0L);
        RepairTargetScope targetScope = new RepairTargetScope(invId, "tenant-1", 100L, 0L);
        ApprovalEvidenceSnapshot approvalEvidence = new ApprovalEvidenceSnapshot(ticket, round, invId, "tenant-1", 100L, 0L);

        assertThatThrownBy(() -> discoveryService.discoverAndVerifyRetry(
            List.of(qCarrier), parentInvoice, List.of(), targetScope, approvalEvidence
        )).isInstanceOf(SecurityException.class)
          .hasMessageContaining("法人スコープがコマンド対象と一致しません");
    }

    @Test
    @DisplayName("重試異常系: 載体修復前版 (vBf) 承認版不一致の場合は OptimisticLockingFailureException を送出すること")
    void testRetryDiscovery_vBfMismatchRejection() {
        long invId = 10089L;
        String ticket = "APPR-001";
        int round = 1;
        String digest = codec.calculateQueueDigest(List.of(100L));
        CarrierEvidence carrierEv = new CarrierEvidence(1, ticket, round, invId, "tenant-1", 100L, "P1", 0L, 0L, 1, digest, 1L, "2026-10-28T16:30:00Z");

        ScopeRepairQueueSnapshot qCarrier = new ScopeRepairQueueSnapshot(100L, "DIGITAL_INVOICE", invId, "RESOLVED", codec.encodeCarrier(carrierEv), 1L);
        ParentInvoiceSnapshot parentInvoice = new ParentInvoiceSnapshot(invId, "tenant-1", 100L, 0L);
        RepairTargetScope targetScope = new RepairTargetScope(invId, "tenant-1", 100L, 0L);
        // 承認正本の targetVersion が 2 (vBf=0 と不一致)
        ApprovalEvidenceSnapshot approvalEvidence = new ApprovalEvidenceSnapshot(ticket, round, invId, "tenant-1", 100L, 2L);

        assertThatThrownBy(() -> discoveryService.discoverAndVerifyRetry(
            List.of(qCarrier), parentInvoice, List.of(), targetScope, approvalEvidence
        )).isInstanceOf(OptimisticLockingFailureException.class)
          .hasMessageContaining("修復前版が決裁承認対象バージョンと一致しません");
    }

    @Test
    @DisplayName("重試異常系: P1 で vAf != vBf の場合は IllegalStateException を送出すること")
    void testRetryDiscovery_p1VersionInconsistencyRejection() {
        long invId = 10089L;
        String ticket = "APPR-001";
        int round = 1;
        String digest = codec.calculateQueueDigest(List.of(100L));
        // P1 なのに vBf=0, vAf=1 となっている矛盾
        CarrierEvidence carrierEv = new CarrierEvidence(1, ticket, round, invId, "tenant-1", 100L, "P1", 0L, 1L, 1, digest, 1L, "2026-10-28T16:30:00Z");

        ScopeRepairQueueSnapshot qCarrier = new ScopeRepairQueueSnapshot(100L, "DIGITAL_INVOICE", invId, "RESOLVED", codec.encodeCarrier(carrierEv), 1L);
        ParentInvoiceSnapshot parentInvoice = new ParentInvoiceSnapshot(invId, "tenant-1", 100L, 1L);
        RepairTargetScope targetScope = new RepairTargetScope(invId, "tenant-1", 100L, 0L);
        ApprovalEvidenceSnapshot approvalEvidence = new ApprovalEvidenceSnapshot(ticket, round, invId, "tenant-1", 100L, 0L);

        assertThatThrownBy(() -> discoveryService.discoverAndVerifyRetry(
            List.of(qCarrier), parentInvoice, List.of(), targetScope, approvalEvidence
        )).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("P1 載体の修復後バージョンが修復前バージョンと一致しません");
    }

    @Test
    @DisplayName("重試異常系: P2 で vAf != vBf + 1 の場合は IllegalStateException を送出すること")
    void testRetryDiscovery_p2VersionInconsistencyRejection() {
        long invId = 10089L;
        String ticket = "APPR-001";
        int round = 1;
        String digest = codec.calculateQueueDigest(List.of(100L));
        // P2 なのに vBf=0, vAf=0 (+0) となっている矛盾
        CarrierEvidence carrierEv = new CarrierEvidence(1, ticket, round, invId, "tenant-1", 100L, "P2", 0L, 0L, 1, digest, 1L, "2026-10-28T16:30:00Z");

        ScopeRepairQueueSnapshot qCarrier = new ScopeRepairQueueSnapshot(100L, "DIGITAL_INVOICE", invId, "RESOLVED", codec.encodeCarrier(carrierEv), 1L);
        ParentInvoiceSnapshot parentInvoice = new ParentInvoiceSnapshot(invId, "tenant-1", 100L, 0L);
        RepairTargetScope targetScope = new RepairTargetScope(invId, "tenant-1", 100L, 0L);
        ApprovalEvidenceSnapshot approvalEvidence = new ApprovalEvidenceSnapshot(ticket, round, invId, "tenant-1", 100L, 0L);

        assertThatThrownBy(() -> discoveryService.discoverAndVerifyRetry(
            List.of(qCarrier), parentInvoice, List.of(), targetScope, approvalEvidence
        )).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("P2 載体の修復後バージョンが修復前バージョン+1と一致しません");
    }

    @Test
    @DisplayName("重試異常系: 親インボイスDB実バージョン不一致 (vAf 不一致) は OptimisticLockingFailureException を送出すること")
    void testRetryDiscovery_parentDbVersionMismatchRejection() {
        long invId = 10089L;
        String ticket = "APPR-001";
        int round = 1;
        String digest = codec.calculateQueueDigest(List.of(100L));
        CarrierEvidence carrierEv = new CarrierEvidence(1, ticket, round, invId, "tenant-1", 100L, "P1", 0L, 0L, 1, digest, 1L, "2026-10-28T16:30:00Z");

        ScopeRepairQueueSnapshot qCarrier = new ScopeRepairQueueSnapshot(100L, "DIGITAL_INVOICE", invId, "RESOLVED", codec.encodeCarrier(carrierEv), 1L);
        // DB 実バージョンが 2（外部変更検知）
        ParentInvoiceSnapshot parentInvoice = new ParentInvoiceSnapshot(invId, "tenant-1", 100L, 2L);
        RepairTargetScope targetScope = new RepairTargetScope(invId, "tenant-1", 100L, 0L);
        ApprovalEvidenceSnapshot approvalEvidence = new ApprovalEvidenceSnapshot(ticket, round, invId, "tenant-1", 100L, 0L);

        assertThatThrownBy(() -> discoveryService.discoverAndVerifyRetry(
            List.of(qCarrier), parentInvoice, List.of(), targetScope, approvalEvidence
        )).isInstanceOf(OptimisticLockingFailureException.class)
          .hasMessageContaining("親インボイスの現在バージョンが載体修復後バージョンと一致しません");
    }

    @Test
    @DisplayName("重試異常系: 親インボイスDB現在スコープ不一致は SecurityException を送出すること")
    void testRetryDiscovery_parentDbScopeMismatchRejection() {
        long invId = 10089L;
        String ticket = "APPR-001";
        int round = 1;
        String digest = codec.calculateQueueDigest(List.of(100L));
        CarrierEvidence carrierEv = new CarrierEvidence(1, ticket, round, invId, "tenant-1", 100L, "P1", 0L, 0L, 1, digest, 1L, "2026-10-28T16:30:00Z");

        ScopeRepairQueueSnapshot qCarrier = new ScopeRepairQueueSnapshot(100L, "DIGITAL_INVOICE", invId, "RESOLVED", codec.encodeCarrier(carrierEv), 1L);
        // DB 実スコープが別テナント
        ParentInvoiceSnapshot parentInvoice = new ParentInvoiceSnapshot(invId, "tenant-other", 100L, 0L);
        RepairTargetScope targetScope = new RepairTargetScope(invId, "tenant-1", 100L, 0L);
        ApprovalEvidenceSnapshot approvalEvidence = new ApprovalEvidenceSnapshot(ticket, round, invId, "tenant-1", 100L, 0L);

        assertThatThrownBy(() -> discoveryService.discoverAndVerifyRetry(
            List.of(qCarrier), parentInvoice, List.of(), targetScope, approvalEvidence
        )).isInstanceOf(SecurityException.class)
          .hasMessageContaining("親インボイスのDB現在スコープが修復ターゲットと一致しません");
    }

    @Test
    @DisplayName("重試異常系: 子イベントDB現在スコープ不一致は SecurityException を送出すること")
    void testRetryDiscovery_childDbScopeMismatchRejection() {
        long invId = 10089L;
        String ticket = "APPR-001";
        int round = 1;
        String digest = codec.calculateQueueDigest(List.of(100L));
        CarrierEvidence carrierEv = new CarrierEvidence(1, ticket, round, invId, "tenant-1", 100L, "P1", 0L, 0L, 1, digest, 1L, "2026-10-28T16:30:00Z");

        ScopeRepairQueueSnapshot qCarrier = new ScopeRepairQueueSnapshot(100L, "DIGITAL_INVOICE", invId, "RESOLVED", codec.encodeCarrier(carrierEv), 1L);
        ParentInvoiceSnapshot parentInvoice = new ParentInvoiceSnapshot(invId, "tenant-1", 100L, 0L);
        // 子イベントの法人スコープがターゲットと相違
        ChildInvoiceEventSnapshot child = new ChildInvoiceEventSnapshot(2001L, invId, "tenant-1", 999L);
        RepairTargetScope targetScope = new RepairTargetScope(invId, "tenant-1", 100L, 0L);
        ApprovalEvidenceSnapshot approvalEvidence = new ApprovalEvidenceSnapshot(ticket, round, invId, "tenant-1", 100L, 0L);

        assertThatThrownBy(() -> discoveryService.discoverAndVerifyRetry(
            List.of(qCarrier), parentInvoice, List.of(child), targetScope, approvalEvidence
        )).isInstanceOf(SecurityException.class)
          .hasMessageContaining("子イベントのDB現在スコープが修復ターゲットと一致しません");
    }

    @Test
    @DisplayName("旧自由文混存: 旧キューに [IDEMPOTENT_CLOSE] 等が存在しても新プロトコル標識のみで正しく再構成成功すること")
    void testRetryDiscovery_coexistenceWithHistoricalOrdinaryNotes() {
        long invId = 10089L;
        String ticket = "APPR-001";
        int round = 1;
        String digest = codec.calculateQueueDigest(List.of(100L, 101L));
        CarrierEvidence carrierEv = new CarrierEvidence(1, ticket, round, invId, "tenant-1", 100L, "P1", 0L, 0L, 2, digest, 1L, "2026-10-28T16:30:00Z");
        MemberEvidence memberEv = new MemberEvidence(1, 100L, ticket, round);

        // 歴史的旧キュー (自由テキストメモ保持)
        ScopeRepairQueueSnapshot qOld1 = new ScopeRepairQueueSnapshot(10L, "DIGITAL_INVOICE_EVENT", 2000L, "RESOLVED", "[IDEMPOTENT_CLOSE]", 1L);
        ScopeRepairQueueSnapshot qOld2 = new ScopeRepairQueueSnapshot(11L, "DIGITAL_INVOICE_EVENT", 2001L, "RESOLVED", "Migration patch free text", 1L);

        // 今回チケットの載体および部材
        ScopeRepairQueueSnapshot qCarrier = new ScopeRepairQueueSnapshot(100L, "DIGITAL_INVOICE", invId, "RESOLVED", codec.encodeCarrier(carrierEv), 1L);
        ScopeRepairQueueSnapshot qMember = new ScopeRepairQueueSnapshot(101L, "DIGITAL_INVOICE_EVENT", 2002L, "RESOLVED", codec.encodeMember(memberEv), 1L);

        ParentInvoiceSnapshot parentInvoice = new ParentInvoiceSnapshot(invId, "tenant-1", 100L, 0L);
        ChildInvoiceEventSnapshot child = new ChildInvoiceEventSnapshot(2002L, invId, "tenant-1", 100L);
        RepairTargetScope targetScope = new RepairTargetScope(invId, "tenant-1", 100L, 0L);
        ApprovalEvidenceSnapshot approvalEvidence = new ApprovalEvidenceSnapshot(ticket, round, invId, "tenant-1", 100L, 0L);

        RetryDiscoveryResult result = discoveryService.discoverAndVerifyRetry(
            List.of(qOld1, qOld2, qCarrier, qMember),
            parentInvoice,
            List.of(child),
            targetScope,
            approvalEvidence
        );

        assertThat(result.alreadyResolved()).isTrue();
        assertThat(result.carrierQueue().id()).isEqualTo(100L);
        assertThat(result.reconstructedQueues()).extracting(ScopeRepairQueueSnapshot::id).containsExactly(100L, 101L);
    }

    @Test
    @DisplayName("旧小ID共存: 過去チケットの旧載体 (小ID) が共存していても新チケットの載体を精確一致で特定できること")
    void testRetryDiscovery_coexistenceWithOldSmallerIdQueue() {
        long invId = 10089L;
        String ticketOld = "APPR-OLD-001";
        String ticketNew = "APPR-NEW-002";
        int round = 1;

        // 旧チケットの載体 id=5
        String digestOld = codec.calculateQueueDigest(List.of(5L));
        CarrierEvidence carrierOld = new CarrierEvidence(1, ticketOld, round, invId, "tenant-1", 100L, "P1", 0L, 0L, 1, digestOld, 1L, "2026-10-01T10:00:00Z");
        ScopeRepairQueueSnapshot qOldCarrier = new ScopeRepairQueueSnapshot(5L, "DIGITAL_INVOICE_EVENT", 2000L, "RESOLVED", codec.encodeCarrier(carrierOld), 1L);

        // 新チケットの載体 id=20、部材 id=30
        String digestNew = codec.calculateQueueDigest(List.of(20L, 30L));
        CarrierEvidence carrierNew = new CarrierEvidence(1, ticketNew, round, invId, "tenant-1", 100L, "P1", 0L, 0L, 2, digestNew, 1L, "2026-10-28T16:30:00Z");
        MemberEvidence memberNew = new MemberEvidence(1, 20L, ticketNew, round);
        ScopeRepairQueueSnapshot qNewCarrier = new ScopeRepairQueueSnapshot(20L, "DIGITAL_INVOICE", invId, "RESOLVED", codec.encodeCarrier(carrierNew), 1L);
        ScopeRepairQueueSnapshot qNewMember = new ScopeRepairQueueSnapshot(30L, "DIGITAL_INVOICE_EVENT", 2001L, "RESOLVED", codec.encodeMember(memberNew), 1L);

        ParentInvoiceSnapshot parentInvoice = new ParentInvoiceSnapshot(invId, "tenant-1", 100L, 0L);
        ChildInvoiceEventSnapshot child = new ChildInvoiceEventSnapshot(2001L, invId, "tenant-1", 100L);
        RepairTargetScope targetScope = new RepairTargetScope(invId, "tenant-1", 100L, 0L);
        ApprovalEvidenceSnapshot approvalEvidence = new ApprovalEvidenceSnapshot(ticketNew, round, invId, "tenant-1", 100L, 0L);

        RetryDiscoveryResult result = discoveryService.discoverAndVerifyRetry(
            List.of(qOldCarrier, qNewCarrier, qNewMember),
            parentInvoice,
            List.of(child),
            targetScope,
            approvalEvidence
        );

        assertThat(result.alreadyResolved()).isTrue();
        assertThat(result.carrierQueue().id()).isEqualTo(20L);
        assertThat(result.reconstructedQueues()).extracting(ScopeRepairQueueSnapshot::id).containsExactly(20L, 30L);
    }
}
