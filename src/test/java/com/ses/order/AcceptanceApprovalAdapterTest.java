package com.ses.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.entity.Acceptance;
import com.ses.entity.ApprovalRequest;
import com.ses.mapper.AcceptanceMapper;
import com.ses.service.AcceptanceService;
import com.ses.service.approval.ApprovalSnapshot;
import com.ses.service.impl.AcceptanceApprovalAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** T057定向テスト: 検収取消の承認adapter（L1）。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AcceptanceApprovalAdapterTest {

    @Mock private AcceptanceMapper mapper;
    @Mock private AcceptanceService service;

    private AcceptanceApprovalAdapter adapter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        adapter = new AcceptanceApprovalAdapter(mapper, service, objectMapper);
    }

    private Acceptance acceptance(Long id) {
        Acceptance a = new Acceptance();
        a.setId(id);
        a.setContractId(10L);
        a.setWorkMonth("2026-07");
        a.setStatus("検収済");
        a.setAmountSnapshot(new BigDecimal("600000"));
        a.setVersion(2);
        return a;
    }

    @Test
    @DisplayName("acceptance.cancel: snapshotは金額・状態を載せ、承認適用で applyCancellation を呼ぶ")
    void snapshotAndApply() {
        when(mapper.selectById(1L)).thenReturn(acceptance(1L));
        ApprovalSnapshot snapshot = adapter.snapshot(1L, java.util.Map.of("reason", "数量訂正"));
        assertThat(snapshot.amountSnapshot()).isEqualByComparingTo("600000");
        assertThat(snapshot.targetVersion()).isEqualTo(2L);
        assertThat(snapshot.payload().get("workMonth")).isEqualTo("2026-07");

        ApprovalRequest request = new ApprovalRequest();
        request.setTargetId(1L);
        adapter.applyApproved(request);
        verify(service).applyCancellation(1L);
    }
}
