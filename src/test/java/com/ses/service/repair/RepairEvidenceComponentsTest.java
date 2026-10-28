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
    // 1. コーデック正常系 (Codec Valid Encoding / Decoding)
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
    // 4. コーデック厳格検証 (Strict Parsing & Type Verification)
    // =========================================================================

    @Test
    @DisplayName("厳格構文: プロトコル標識行がJSON不正の場合は CorruptedEvidenceException を送出すること")
    void testParse_corruptedProtocolRecords_syntaxErrorThrows() {
        assertThatThrownBy(() -> codec.parseCarrier("SES_NF09_COMPL:{malformed_json}"))
            .isInstanceOf(CorruptedEvidenceException.class);

        assertThatThrownBy(() -> codec.parseMember("SES_NF09_MEMBER:{malformed_json}"))
            .isInstanceOf(CorruptedEvidenceException.class);
    }

    @Test
    @DisplayName("厳格構文: プロトコルバージョン不一致 (v != 1) の場合は CorruptedEvidenceException を送出すること")
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
    @DisplayName("厳格構文: 必須フィールド欠落時は CorruptedEvidenceException を送出すること")
    void testParse_corruptedProtocolRecords_missingFieldThrows() {
        String missingVbf = "SES_NF09_COMPL:{\"v\":1,\"ticket\":\"T1\",\"round\":1,\"invId\":100,\"tenant\":\"t1\",\"legalEntity\":100,\"pCls\":\"P1\",\"vAf\":0,\"qc\":1,\"qh\":\"...\",\"by\":1,\"at\":\"...\"}";
        assertThatThrownBy(() -> codec.parseCarrier(missingVbf))
            .isInstanceOf(CorruptedEvidenceException.class)
            .hasMessageContaining("必須フィールドが欠落またはNULL");

        String missingCarrierId = "SES_NF09_MEMBER:{\"v\":1,\"ticket\":\"T1\",\"round\":1}";
        assertThatThrownBy(() -> codec.parseMember(missingCarrierId))
            .isInstanceOf(CorruptedEvidenceException.class)
            .hasMessageContaining("必須フィールドが欠落またはNULL");
    }

    @Test
    @DisplayName("厳格構文: 重複キーを含むJSONは CorruptedEvidenceException を送出すること")
    void testParse_duplicateKeysThrows() {
        String digest = codec.calculateQueueDigest(List.of(100L));
        // ticket キーが重複
        String dupKeyJson = "SES_NF09_COMPL:{\"v\":1,\"ticket\":\"T1\",\"ticket\":\"T2\",\"round\":1,\"invId\":100,\"tenant\":\"t1\",\"legalEntity\":100,\"pCls\":\"P1\",\"vBf\":0,\"vAf\":0,\"qc\":1,\"qh\":\""
            + digest + "\",\"by\":1,\"at\":\"2026-10-28T16:30:00Z\"}";

        assertThatThrownBy(() -> codec.parseCarrier(dupKeyJson))
            .isInstanceOf(CorruptedEvidenceException.class);
    }

    @Test
    @DisplayName("厳格構文: JSON末尾に第2のJSONまたは余剰トークンが存在する場合は CorruptedEvidenceException を送出すること")
    void testParse_trailingExtraTokensThrows() {
        String digest = codec.calculateQueueDigest(List.of(100L));
        String trailingJson = "SES_NF09_COMPL:{\"v\":1,\"ticket\":\"T1\",\"round\":1,\"invId\":100,\"tenant\":\"t1\",\"legalEntity\":100,\"pCls\":\"P1\",\"vBf\":0,\"vAf\":0,\"qc\":1,\"qh\":\""
            + digest + "\",\"by\":1,\"at\":\"2026-10-28T16:30:00Z\"} {\"extra\":1}";

        assertThatThrownBy(() -> codec.parseCarrier(trailingJson))
            .isInstanceOf(CorruptedEvidenceException.class)
            .hasMessageContaining("余分なトークンまたは第2のJSONが存在します");
    }

    @Test
    @DisplayName("厳格構文: 数値フィールドが文字列形式で指定された場合は CorruptedEvidenceException を送出すること")
    void testParse_numericStringThrows() {
        String digest = codec.calculateQueueDigest(List.of(100L));
        // round が文字列 "1"
        String numStringJson = "SES_NF09_COMPL:{\"v\":1,\"ticket\":\"T1\",\"round\":\"1\",\"invId\":100,\"tenant\":\"t1\",\"legalEntity\":100,\"pCls\":\"P1\",\"vBf\":0,\"vAf\":0,\"qc\":1,\"qh\":\""
            + digest + "\",\"by\":1,\"at\":\"2026-10-28T16:30:00Z\"}";

        assertThatThrownBy(() -> codec.parseCarrier(numStringJson))
            .isInstanceOf(CorruptedEvidenceException.class)
            .hasMessageContaining("数値フィールドが文字列形式");
    }

    @Test
    @DisplayName("厳格構文: 整数フィールドに小数が指定された場合は CorruptedEvidenceException を送出すること")
    void testParse_floatingPointNumberThrows() {
        String digest = codec.calculateQueueDigest(List.of(100L));
        // round が小数 1.5
        String floatJson = "SES_NF09_COMPL:{\"v\":1,\"ticket\":\"T1\",\"round\":1.5,\"invId\":100,\"tenant\":\"t1\",\"legalEntity\":100,\"pCls\":\"P1\",\"vBf\":0,\"vAf\":0,\"qc\":1,\"qh\":\""
            + digest + "\",\"by\":1,\"at\":\"2026-10-28T16:30:00Z\"}";

        assertThatThrownBy(() -> codec.parseCarrier(floatJson))
            .isInstanceOf(CorruptedEvidenceException.class)
            .hasMessageContaining("小数または浮動小数点数");
    }

    @Test
    @DisplayName("厳格構文: 整数値が型表現範囲外 (オーバーフロー) の場合は CorruptedEvidenceException を送出すること")
    void testParse_overflowIntegerThrows() {
        String digest = codec.calculateQueueDigest(List.of(100L));
        // round (int) に Long.MAX_VALUE 相当の巨大値を指定
        String overflowJson = "SES_NF09_COMPL:{\"v\":1,\"ticket\":\"T1\",\"round\":999999999999999999,\"invId\":100,\"tenant\":\"t1\",\"legalEntity\":100,\"pCls\":\"P1\",\"vBf\":0,\"vAf\":0,\"qc\":1,\"qh\":\""
            + digest + "\",\"by\":1,\"at\":\"2026-10-28T16:30:00Z\"}";

        assertThatThrownBy(() -> codec.parseCarrier(overflowJson))
            .isInstanceOf(CorruptedEvidenceException.class)
            .hasMessageContaining("表現範囲外");
    }

    @Test
    @DisplayName("厳格構文: 結案日時のUTC形式 (Z末尾) が不正な場合は CorruptedEvidenceException を送出すること")
    void testParse_invalidUtcTimestampThrows() {
        String digest = codec.calculateQueueDigest(List.of(100L));
        // Z なしローカル日時
        String noZ = "SES_NF09_COMPL:{\"v\":1,\"ticket\":\"T1\",\"round\":1,\"invId\":100,\"tenant\":\"t1\",\"legalEntity\":100,\"pCls\":\"P1\",\"vBf\":0,\"vAf\":0,\"qc\":1,\"qh\":\""
            + digest + "\",\"by\":1,\"at\":\"2026-10-28 16:30:00\"}";
        assertThatThrownBy(() -> codec.parseCarrier(noZ))
            .isInstanceOf(CorruptedEvidenceException.class)
            .hasMessageContaining("UTC形式 (末尾Z) ではありません");

        // +09:00 表記
        String withTz = "SES_NF09_COMPL:{\"v\":1,\"ticket\":\"T1\",\"round\":1,\"invId\":100,\"tenant\":\"t1\",\"legalEntity\":100,\"pCls\":\"P1\",\"vBf\":0,\"vAf\":0,\"qc\":1,\"qh\":\""
            + digest + "\",\"by\":1,\"at\":\"2026-10-28T16:30:00+09:00\"}";
        assertThatThrownBy(() -> codec.parseCarrier(withTz))
            .isInstanceOf(CorruptedEvidenceException.class)
            .hasMessageContaining("UTC形式 (末尾Z) ではありません");
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
        ScopeRepairQueueSnapshot child20 = new ScopeRepairQueueSnapshot(20L, "DIGITAL_INVOICE_EVENT", 2001L, "PENDING", null, 0L);
        ScopeRepairQueueSnapshot child30 = new ScopeRepairQueueSnapshot(30L, "DIGITAL_INVOICE_EVENT", 2002L, "PENDING", null, 0L);

        ScopeRepairQueueSnapshot selected = InitialCarrierSelector.selectInitialCarrier(null, List.of(child20, child30));
        assertThat(selected.id()).isEqualTo(20L);
    }

    // =========================================================================
    // 6. 入力および集約バインディング検証 (Input & Aggregate Binding)
    // =========================================================================

    @Test
    @DisplayName("入力バインディング: 親インボイスIDが修復ターゲットと不一致の場合は IllegalArgumentException を送出すること")
    void testInputBinding_parentInvoiceIdMismatch_throws() {
        ParentInvoiceSnapshot wrongParent = new ParentInvoiceSnapshot(9999L, "tenant-1", 100L, 0L);
        RepairTargetScope targetScope = new RepairTargetScope(10089L, "tenant-1", 100L, 0L);
        ApprovalEvidenceSnapshot approval = new ApprovalEvidenceSnapshot("APPR-001", 1, 10089L, "tenant-1", 100L, 0L);

        assertThatThrownBy(() -> discoveryService.discoverAndVerifyRetry(
            List.of(), wrongParent, List.of(), targetScope, approval
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("親インボイスIDが修復ターゲットと一致しません");
    }

    @Test
    @DisplayName("入力バインディング: 承認証跡のインボイスIDが修復ターゲットと不一致の場合は IllegalArgumentException を送出すること")
    void testInputBinding_approvalInvoiceIdMismatch_throws() {
        ParentInvoiceSnapshot parent = new ParentInvoiceSnapshot(10089L, "tenant-1", 100L, 0L);
        RepairTargetScope targetScope = new RepairTargetScope(10089L, "tenant-1", 100L, 0L);
        ApprovalEvidenceSnapshot wrongApproval = new ApprovalEvidenceSnapshot("APPR-001", 1, 9999L, "tenant-1", 100L, 0L);

        assertThatThrownBy(() -> discoveryService.discoverAndVerifyRetry(
            List.of(), parent, List.of(), targetScope, wrongApproval
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("決裁承認証跡のインボイスIDが修復ターゲットと一致しません");
    }

    @Test
    @DisplayName("入力バインディング: 承認証跡のテナントスコープ不一致は SecurityException を送出すること")
    void testInputBinding_approvalTenantMismatch_throws() {
        ParentInvoiceSnapshot parent = new ParentInvoiceSnapshot(10089L, "tenant-1", 100L, 0L);
        RepairTargetScope targetScope = new RepairTargetScope(10089L, "tenant-1", 100L, 0L);
        ApprovalEvidenceSnapshot wrongTenantApproval = new ApprovalEvidenceSnapshot("APPR-001", 1, 10089L, "tenant-9", 100L, 0L);

        assertThatThrownBy(() -> discoveryService.discoverAndVerifyRetry(
            List.of(), parent, List.of(), targetScope, wrongTenantApproval
        )).isInstanceOf(SecurityException.class)
          .hasMessageContaining("決裁承認証跡のテナントスコープが修復ターゲットと一致しません");
    }

    @Test
    @DisplayName("入力バインディング: 承認証跡の法人スコープ不一致は SecurityException を送出すること")
    void testInputBinding_approvalLegalEntityMismatch_throws() {
        ParentInvoiceSnapshot parent = new ParentInvoiceSnapshot(10089L, "tenant-1", 100L, 0L);
        RepairTargetScope targetScope = new RepairTargetScope(10089L, "tenant-1", 100L, 0L);
        ApprovalEvidenceSnapshot wrongEntityApproval = new ApprovalEvidenceSnapshot("APPR-001", 1, 10089L, "tenant-1", 999L, 0L);

        assertThatThrownBy(() -> discoveryService.discoverAndVerifyRetry(
            List.of(), parent, List.of(), targetScope, wrongEntityApproval
        )).isInstanceOf(SecurityException.class)
          .hasMessageContaining("決裁承認証跡の法人スコープが修復ターゲットと一致しません");
    }

    @Test
    @DisplayName("入力バインディング: childEvents が null の場合は NullPointerException を送出すること")
    void testInputBinding_childEventsNull_throws() {
        ParentInvoiceSnapshot parent = new ParentInvoiceSnapshot(10089L, "tenant-1", 100L, 0L);
        RepairTargetScope targetScope = new RepairTargetScope(10089L, "tenant-1", 100L, 0L);
        ApprovalEvidenceSnapshot approval = new ApprovalEvidenceSnapshot("APPR-001", 1, 10089L, "tenant-1", 100L, 0L);

        assertThatThrownBy(() -> discoveryService.discoverAndVerifyRetry(
            List.of(), parent, null, targetScope, approval
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("入力バインディング: 子イベントが親インボイスに属していない場合は IllegalArgumentException を送出すること")
    void testInputBinding_childEventWrongParentInvoice_throws() {
        ParentInvoiceSnapshot parent = new ParentInvoiceSnapshot(10089L, "tenant-1", 100L, 0L);
        ChildInvoiceEventSnapshot wrongChild = new ChildInvoiceEventSnapshot(2001L, 9999L, "tenant-1", 100L);
        RepairTargetScope targetScope = new RepairTargetScope(10089L, "tenant-1", 100L, 0L);
        ApprovalEvidenceSnapshot approval = new ApprovalEvidenceSnapshot("APPR-001", 1, 10089L, "tenant-1", 100L, 0L);

        assertThatThrownBy(() -> discoveryService.discoverAndVerifyRetry(
            List.of(), parent, List.of(wrongChild), targetScope, approval
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("子イベントが親インボイスに属していません");
    }

    @Test
    @DisplayName("入力バインディング: 親キューのエンティティIDが対象インボイスと一致しない場合は IllegalArgumentException を送出すること")
    void testInputBinding_parentQueueWrongEntityId_throws() {
        ParentInvoiceSnapshot parent = new ParentInvoiceSnapshot(10089L, "tenant-1", 100L, 0L);
        RepairTargetScope targetScope = new RepairTargetScope(10089L, "tenant-1", 100L, 0L);
        ApprovalEvidenceSnapshot approval = new ApprovalEvidenceSnapshot("APPR-001", 1, 10089L, "tenant-1", 100L, 0L);
        // 親キューなのに entityId=9999
        ScopeRepairQueueSnapshot wrongParentQueue = new ScopeRepairQueueSnapshot(100L, "DIGITAL_INVOICE", 9999L, "PENDING", null, 0L);

        assertThatThrownBy(() -> discoveryService.discoverAndVerifyRetry(
            List.of(wrongParentQueue), parent, List.of(), targetScope, approval
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("親キューのエンティティIDが対象インボイスIDと一致しません");
    }

    @Test
    @DisplayName("入力バインディング: 子キューのエンティティIDが入力子イベント一覧に不在の場合は IllegalArgumentException を送出すること")
    void testInputBinding_childQueueMissingInChildEvents_throws() {
        ParentInvoiceSnapshot parent = new ParentInvoiceSnapshot(10089L, "tenant-1", 100L, 0L);
        ChildInvoiceEventSnapshot child = new ChildInvoiceEventSnapshot(2001L, 10089L, "tenant-1", 100L);
        RepairTargetScope targetScope = new RepairTargetScope(10089L, "tenant-1", 100L, 0L);
        ApprovalEvidenceSnapshot approval = new ApprovalEvidenceSnapshot("APPR-001", 1, 10089L, "tenant-1", 100L, 0L);
        // 子キューの entityId=8888 が childEvents に存在しない
        ScopeRepairQueueSnapshot orphanChildQueue = new ScopeRepairQueueSnapshot(101L, "DIGITAL_INVOICE_EVENT", 8888L, "PENDING", null, 0L);

        assertThatThrownBy(() -> discoveryService.discoverAndVerifyRetry(
            List.of(orphanChildQueue), parent, List.of(child), targetScope, approval
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("子キューのエンティティIDが入力子イベント一覧に存在しません");
    }

    @Test
    @DisplayName("入力バインディング: キューのエンティティ種別が不正な場合は IllegalArgumentException を送出すること")
    void testInputBinding_queueUnknownEntityType_throws() {
        ParentInvoiceSnapshot parent = new ParentInvoiceSnapshot(10089L, "tenant-1", 100L, 0L);
        RepairTargetScope targetScope = new RepairTargetScope(10089L, "tenant-1", 100L, 0L);
        ApprovalEvidenceSnapshot approval = new ApprovalEvidenceSnapshot("APPR-001", 1, 10089L, "tenant-1", 100L, 0L);
        ScopeRepairQueueSnapshot unknownTypeQueue = new ScopeRepairQueueSnapshot(100L, "UNKNOWN_ENTITY", 10089L, "PENDING", null, 0L);

        assertThatThrownBy(() -> discoveryService.discoverAndVerifyRetry(
            List.of(unknownTypeQueue), parent, List.of(), targetScope, approval
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("修復キューのエンティティ種別が不正です");
    }

    @Test
    @DisplayName("入力バインディング: 重複する修復キューIDが存在する場合は IllegalArgumentException を送出すること")
    void testInputBinding_duplicateQueueId_throws() {
        ParentInvoiceSnapshot parent = new ParentInvoiceSnapshot(10089L, "tenant-1", 100L, 0L);
        ChildInvoiceEventSnapshot child = new ChildInvoiceEventSnapshot(2001L, 10089L, "tenant-1", 100L);
        RepairTargetScope targetScope = new RepairTargetScope(10089L, "tenant-1", 100L, 0L);
        ApprovalEvidenceSnapshot approval = new ApprovalEvidenceSnapshot("APPR-001", 1, 10089L, "tenant-1", 100L, 0L);

        // 共に id=100L
        ScopeRepairQueueSnapshot q1 = new ScopeRepairQueueSnapshot(100L, "DIGITAL_INVOICE", 10089L, "PENDING", null, 0L);
        ScopeRepairQueueSnapshot q2 = new ScopeRepairQueueSnapshot(100L, "DIGITAL_INVOICE_EVENT", 2001L, "PENDING", null, 0L);

        assertThatThrownBy(() -> discoveryService.discoverAndVerifyRetry(
            List.of(q1, q2), parent, List.of(child), targetScope, approval
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("重複する修復キューIDが検知されました");
    }

    @Test
    @DisplayName("入力バインディング: 重複する子イベントIDが存在する場合は IllegalArgumentException を送出すること")
    void testInputBinding_duplicateChildEventId_throws() {
        ParentInvoiceSnapshot parent = new ParentInvoiceSnapshot(10089L, "tenant-1", 100L, 0L);
        ChildInvoiceEventSnapshot child1 = new ChildInvoiceEventSnapshot(2001L, 10089L, "tenant-1", 100L);
        ChildInvoiceEventSnapshot child2 = new ChildInvoiceEventSnapshot(2001L, 10089L, "tenant-1", 100L);
        RepairTargetScope targetScope = new RepairTargetScope(10089L, "tenant-1", 100L, 0L);
        ApprovalEvidenceSnapshot approval = new ApprovalEvidenceSnapshot("APPR-001", 1, 10089L, "tenant-1", 100L, 0L);

        assertThatThrownBy(() -> discoveryService.discoverAndVerifyRetry(
            List.of(), parent, List.of(child1, child2), targetScope, approval
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("重複する子イベントIDが検知されました");
    }

    @Test
    @DisplayName("入力バインディング: 同一エンティティに対して重複するキュー行が存在する場合は IllegalArgumentException を送出すること")
    void testInputBinding_conflictingEntityQueueMapping_throws() {
        ParentInvoiceSnapshot parent = new ParentInvoiceSnapshot(10089L, "tenant-1", 100L, 0L);
        ChildInvoiceEventSnapshot child = new ChildInvoiceEventSnapshot(2001L, 10089L, "tenant-1", 100L);
        RepairTargetScope targetScope = new RepairTargetScope(10089L, "tenant-1", 100L, 0L);
        ApprovalEvidenceSnapshot approval = new ApprovalEvidenceSnapshot("APPR-001", 1, 10089L, "tenant-1", 100L, 0L);

        // 同じ childEvent (2001L) に対しキューが 2 行存在する
        ScopeRepairQueueSnapshot q1 = new ScopeRepairQueueSnapshot(101L, "DIGITAL_INVOICE_EVENT", 2001L, "PENDING", null, 0L);
        ScopeRepairQueueSnapshot q2 = new ScopeRepairQueueSnapshot(102L, "DIGITAL_INVOICE_EVENT", 2001L, "PENDING", null, 0L);

        assertThatThrownBy(() -> discoveryService.discoverAndVerifyRetry(
            List.of(q1, q2), parent, List.of(child), targetScope, approval
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("同一エンティティに対する重複キュー行が検知されました");
    }

    // =========================================================================
    // 7. 事前スキャン・走査順序検証 (Pre-scan Validation)
    // =========================================================================

    @Test
    @DisplayName("走査順序: 載体不在時であっても孤立した破損部材レコードが存在すれば事前に CorruptedEvidenceException で拒絶されること")
    void testPreScan_isolatedCorruptedMemberThrows() {
        ParentInvoiceSnapshot parent = new ParentInvoiceSnapshot(10089L, "tenant-1", 100L, 0L);
        ChildInvoiceEventSnapshot child = new ChildInvoiceEventSnapshot(2001L, 10089L, "tenant-1", 100L);
        RepairTargetScope targetScope = new RepairTargetScope(10089L, "tenant-1", 100L, 0L);
        ApprovalEvidenceSnapshot approval = new ApprovalEvidenceSnapshot("APPR-001", 1, 10089L, "tenant-1", 100L, 0L);

        // 載体は存在しないが、部材キューに破損JSON（SES_NF09_MEMBER:{broken）が存在
        ScopeRepairQueueSnapshot corruptedMemberQueue = new ScopeRepairQueueSnapshot(
            101L, "DIGITAL_INVOICE_EVENT", 2001L, "RESOLVED", "SES_NF09_MEMBER:{broken_json}", 1L
        );

        assertThatThrownBy(() -> discoveryService.discoverAndVerifyRetry(
            List.of(corruptedMemberQueue), parent, List.of(child), targetScope, approval
        )).isInstanceOf(CorruptedEvidenceException.class);
    }

    @Test
    @DisplayName("走査順序: PENDING キュー上に破損プロトコル証跡が存在する場合は carrier 探索前に即時拒絶されること")
    void testPreScan_corruptedProtocolOnPendingQueueThrows() {
        ParentInvoiceSnapshot parent = new ParentInvoiceSnapshot(10089L, "tenant-1", 100L, 0L);
        RepairTargetScope targetScope = new RepairTargetScope(10089L, "tenant-1", 100L, 0L);
        ApprovalEvidenceSnapshot approval = new ApprovalEvidenceSnapshot("APPR-001", 1, 10089L, "tenant-1", 100L, 0L);

        // PENDING なのに破損プロトコル標識行を保持
        ScopeRepairQueueSnapshot pendingCorrupted = new ScopeRepairQueueSnapshot(
            100L, "DIGITAL_INVOICE", 10089L, "PENDING", "SES_NF09_COMPL:{broken_json}", 0L
        );

        assertThatThrownBy(() -> discoveryService.discoverAndVerifyRetry(
            List.of(pendingCorrupted), parent, List.of(), targetScope, approval
        )).isInstanceOf(CorruptedEvidenceException.class);
    }

    @Test
    @DisplayName("走査順序: キューが PENDING/RESOLVED 以外の未知状態の場合は IllegalStateException で即時拒絶されること")
    void testPreScan_unknownQueueStatusThrows() {
        ParentInvoiceSnapshot parent = new ParentInvoiceSnapshot(10089L, "tenant-1", 100L, 0L);
        RepairTargetScope targetScope = new RepairTargetScope(10089L, "tenant-1", 100L, 0L);
        ApprovalEvidenceSnapshot approval = new ApprovalEvidenceSnapshot("APPR-001", 1, 10089L, "tenant-1", 100L, 0L);

        ScopeRepairQueueSnapshot invalidStatusQueue = new ScopeRepairQueueSnapshot(
            100L, "DIGITAL_INVOICE", 10089L, "UNKNOWN_STATUS", null, 0L
        );

        assertThatThrownBy(() -> discoveryService.discoverAndVerifyRetry(
            List.of(invalidStatusQueue), parent, List.of(), targetScope, approval
        )).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("修復キューのステータスが不正です");
    }

    // =========================================================================
    // 8. 重試発見・再構成・全不変条件検証 (Retry Invariants & Reconstitution)
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

        ChildInvoiceEventSnapshot child = new ChildInvoiceEventSnapshot(2001L, invId, "tenant-1", 100L);
        ScopeRepairQueueSnapshot qCarrier1 = new ScopeRepairQueueSnapshot(100L, "DIGITAL_INVOICE", invId, "RESOLVED", codec.encodeCarrier(carrierEv), 1L);
        ScopeRepairQueueSnapshot qCarrier2 = new ScopeRepairQueueSnapshot(101L, "DIGITAL_INVOICE_EVENT", 2001L, "RESOLVED", codec.encodeCarrier(carrierEv), 1L);

        ParentInvoiceSnapshot parentInvoice = new ParentInvoiceSnapshot(invId, "tenant-1", 100L, 0L);
        RepairTargetScope targetScope = new RepairTargetScope(invId, "tenant-1", 100L, 0L);
        ApprovalEvidenceSnapshot approvalEvidence = new ApprovalEvidenceSnapshot(ticket, round, invId, "tenant-1", 100L, 0L);

        assertThatThrownBy(() -> discoveryService.discoverAndVerifyRetry(
            List.of(qCarrier1, qCarrier2), parentInvoice, List.of(child), targetScope, approvalEvidence
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
        MemberEvidence conflictMember = new MemberEvidence(1, 999L, ticket, round);

        ChildInvoiceEventSnapshot child = new ChildInvoiceEventSnapshot(2001L, invId, "tenant-1", 100L);
        ScopeRepairQueueSnapshot qCarrier = new ScopeRepairQueueSnapshot(100L, "DIGITAL_INVOICE", invId, "RESOLVED", codec.encodeCarrier(carrierEv), 1L);
        ScopeRepairQueueSnapshot qMember = new ScopeRepairQueueSnapshot(101L, "DIGITAL_INVOICE_EVENT", 2001L, "RESOLVED", codec.encodeMember(conflictMember), 1L);

        ParentInvoiceSnapshot parentInvoice = new ParentInvoiceSnapshot(invId, "tenant-1", 100L, 0L);
        RepairTargetScope targetScope = new RepairTargetScope(invId, "tenant-1", 100L, 0L);
        ApprovalEvidenceSnapshot approvalEvidence = new ApprovalEvidenceSnapshot(ticket, round, invId, "tenant-1", 100L, 0L);

        assertThatThrownBy(() -> discoveryService.discoverAndVerifyRetry(
            List.of(qCarrier, qMember), parentInvoice, List.of(child), targetScope, approvalEvidence
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
        CarrierEvidence carrierEv = new CarrierEvidence(1, ticket, round, invId, "tenant-1", 100L, "P1", 0L, 0L, 3, digest, 1L, "2026-10-28T16:30:00Z");
        MemberEvidence memberEv = new MemberEvidence(1, 100L, ticket, round);

        ChildInvoiceEventSnapshot child = new ChildInvoiceEventSnapshot(2001L, invId, "tenant-1", 100L);
        ScopeRepairQueueSnapshot qCarrier = new ScopeRepairQueueSnapshot(100L, "DIGITAL_INVOICE", invId, "RESOLVED", codec.encodeCarrier(carrierEv), 1L);
        ScopeRepairQueueSnapshot qMember = new ScopeRepairQueueSnapshot(101L, "DIGITAL_INVOICE_EVENT", 2001L, "RESOLVED", codec.encodeMember(memberEv), 1L);

        ParentInvoiceSnapshot parentInvoice = new ParentInvoiceSnapshot(invId, "tenant-1", 100L, 0L);
        RepairTargetScope targetScope = new RepairTargetScope(invId, "tenant-1", 100L, 0L);
        ApprovalEvidenceSnapshot approvalEvidence = new ApprovalEvidenceSnapshot(ticket, round, invId, "tenant-1", 100L, 0L);

        assertThatThrownBy(() -> discoveryService.discoverAndVerifyRetry(
            List.of(qCarrier, qMember), parentInvoice, List.of(child), targetScope, approvalEvidence
        )).isInstanceOf(CorruptedEvidenceException.class)
          .hasMessageContaining("件数が載体の記録と不一致");
    }

    @Test
    @DisplayName("重試異常系: 暗号学的ハッシュ不一致 (qh 不一致) の場合は CorruptedEvidenceException を送出すること")
    void testRetryDiscovery_hashMismatchRejection() {
        long invId = 10089L;
        String ticket = "APPR-001";
        int round = 1;
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
    @DisplayName("重試異常系: 未知修復分類 pCls は CorruptedEvidenceException を送出すること")
    void testRetryDiscovery_unknownPClsRejection() {
        String digest = codec.calculateQueueDigest(List.of(100L));
        String unknownPClsJson = "SES_NF09_COMPL:{\"v\":1,\"ticket\":\"T1\",\"round\":1,\"invId\":100,\"tenant\":\"t1\",\"legalEntity\":100,\"pCls\":\"P9\",\"vBf\":0,\"vAf\":0,\"qc\":1,\"qh\":\""
            + digest + "\",\"by\":1,\"at\":\"2026-10-28T16:30:00Z\"}";

        assertThatThrownBy(() -> codec.parseCarrier(unknownPClsJson))
            .isInstanceOf(CorruptedEvidenceException.class)
            .hasMessageContaining("不正な修復分類");
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

        ChildInvoiceEventSnapshot child2000 = new ChildInvoiceEventSnapshot(2000L, invId, "tenant-1", 100L);
        ChildInvoiceEventSnapshot child2001 = new ChildInvoiceEventSnapshot(2001L, invId, "tenant-1", 100L);
        ChildInvoiceEventSnapshot child2002 = new ChildInvoiceEventSnapshot(2002L, invId, "tenant-1", 100L);

        ScopeRepairQueueSnapshot qOld1 = new ScopeRepairQueueSnapshot(10L, "DIGITAL_INVOICE_EVENT", 2000L, "RESOLVED", "[IDEMPOTENT_CLOSE]", 1L);
        ScopeRepairQueueSnapshot qOld2 = new ScopeRepairQueueSnapshot(11L, "DIGITAL_INVOICE_EVENT", 2001L, "RESOLVED", "Migration patch free text", 1L);
        ScopeRepairQueueSnapshot qCarrier = new ScopeRepairQueueSnapshot(100L, "DIGITAL_INVOICE", invId, "RESOLVED", codec.encodeCarrier(carrierEv), 1L);
        ScopeRepairQueueSnapshot qMember = new ScopeRepairQueueSnapshot(101L, "DIGITAL_INVOICE_EVENT", 2002L, "RESOLVED", codec.encodeMember(memberEv), 1L);

        ParentInvoiceSnapshot parentInvoice = new ParentInvoiceSnapshot(invId, "tenant-1", 100L, 0L);
        RepairTargetScope targetScope = new RepairTargetScope(invId, "tenant-1", 100L, 0L);
        ApprovalEvidenceSnapshot approvalEvidence = new ApprovalEvidenceSnapshot(ticket, round, invId, "tenant-1", 100L, 0L);

        RetryDiscoveryResult result = discoveryService.discoverAndVerifyRetry(
            List.of(qOld1, qOld2, qCarrier, qMember),
            parentInvoice,
            List.of(child2000, child2001, child2002),
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

        ChildInvoiceEventSnapshot child2000 = new ChildInvoiceEventSnapshot(2000L, invId, "tenant-1", 100L);
        ChildInvoiceEventSnapshot child2001 = new ChildInvoiceEventSnapshot(2001L, invId, "tenant-1", 100L);

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
        RepairTargetScope targetScope = new RepairTargetScope(invId, "tenant-1", 100L, 0L);
        ApprovalEvidenceSnapshot approvalEvidence = new ApprovalEvidenceSnapshot(ticketNew, round, invId, "tenant-1", 100L, 0L);

        RetryDiscoveryResult result = discoveryService.discoverAndVerifyRetry(
            List.of(qOldCarrier, qNewCarrier, qNewMember),
            parentInvoice,
            List.of(child2000, child2001),
            targetScope,
            approvalEvidence
        );

        assertThat(result.alreadyResolved()).isTrue();
        assertThat(result.carrierQueue().id()).isEqualTo(20L);
        assertThat(result.reconstructedQueues()).extracting(ScopeRepairQueueSnapshot::id).containsExactly(20L, 30L);
    }
}
