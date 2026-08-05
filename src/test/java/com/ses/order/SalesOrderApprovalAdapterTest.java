package com.ses.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.entity.ApprovalRequest;
import com.ses.entity.SalesOrder;
import com.ses.mapper.SalesOrderMapper;
import com.ses.service.SalesOrderService;
import com.ses.service.approval.ApprovalSnapshot;
import com.ses.service.impl.SalesOrderApprovalAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** T055定向テスト: 注文取消・条件差分の承認adapter（L1〜L2）。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SalesOrderApprovalAdapterTest {

    @Mock private SalesOrderMapper mapper;
    @Mock private SalesOrderService service;

    private SalesOrderApprovalAdapter adapter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        adapter = new SalesOrderApprovalAdapter(mapper, service, objectMapper);
    }

    private SalesOrder order(Long id) {
        SalesOrder o = new SalesOrder();
        o.setId(id);
        o.setOrderNo("O-202608-0001");
        o.setCustomerPoNo("PO-100");
        o.setStatus("契約化");
        o.setTotalAmountSnapshot(new BigDecimal("1100000"));
        o.setVersion(3);
        return o;
    }

    @Test
    @DisplayName("order.cancel: snapshotは注文状態と金額を載せ、承認適用で applyCancellation を呼ぶ")
    void cancelSnapshotAndApply() {
        when(mapper.selectById(1L)).thenReturn(order(1L));
        ApprovalSnapshot snapshot = adapter.snapshot(1L, Map.of("reason", "顧客都合"));
        assertThat(snapshot.amountSnapshot()).isEqualByComparingTo("1100000");
        assertThat(snapshot.targetVersion()).isEqualTo(3L);
        assertThat(snapshot.payload().get("operation")).isEqualTo("cancel");
        assertThat(snapshot.diff().get("operation")).isNotNull();

        ApprovalRequest request = new ApprovalRequest();
        request.setTargetId(1L);
        request.setPayloadJson("{\"operation\":\"cancel\"}");
        adapter.applyApproved(request);
        verify(service).applyCancellation(1L);
    }

    @Test
    @DisplayName("order.conditionDiff: 承認適用は注文を変更せず監査証跡として終わる")
    void conditionDiffApplyDoesNotMutateOrder() {
        when(mapper.selectById(2L)).thenReturn(order(2L));
        ApprovalSnapshot snapshot = adapter.snapshot(2L, Map.of("operation", "conditionDiff", "reason", "単価改定"));
        assertThat(snapshot.payload().get("operation")).isEqualTo("conditionDiff");

        ApprovalRequest request = new ApprovalRequest();
        request.setTargetId(2L);
        request.setPayloadJson("{\"operation\":\"conditionDiff\"}");
        adapter.applyApproved(request);
        // 注文状態は変更しない（createContractDrafts が承認済みを確認する）
        verify(service, org.mockito.Mockito.never()).applyCancellation(org.mockito.ArgumentMatchers.anyLong());
    }
}
