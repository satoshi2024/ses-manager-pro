package com.ses.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.Acceptance;
import com.ses.entity.ApprovalRequest;
import com.ses.mapper.AcceptanceMapper;
import com.ses.service.AcceptanceService;
import com.ses.service.approval.ApprovalOrganizationResolver;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** T057定向テスト: 検収取消の承認adapter（L1）。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AcceptanceApprovalAdapterTest {

    @Mock private AcceptanceMapper mapper;
    @Mock private AcceptanceService service;
    @Mock private ApprovalOrganizationResolver organizationResolver;

    private AcceptanceApprovalAdapter adapter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        adapter = new AcceptanceApprovalAdapter(mapper, service, objectMapper, organizationResolver);
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
    @DisplayName("R09-P1-02: scope外の検収は承認申請を作れない（assertAllowedAcceptanceが拒否）")
    void cancelRejectsScopeOutsideAcceptance() {
        when(mapper.selectByIdForUpdate(2L)).thenReturn(acceptance(2L));
        org.mockito.Mockito.doThrow(new BusinessException(404, "error.scope.notFound"))
                .when(service).assertAllowedAcceptance(2L);
        assertThatThrownBy(() -> adapter.snapshot(2L, java.util.Map.of()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("R09-P1-02: 検収済以外（提出済等）の検収は取消申請を作れない")
    void cancelRejectsNonAccepted() {
        Acceptance submitted = acceptance(3L);
        submitted.setStatus("提出済");
        when(mapper.selectByIdForUpdate(3L)).thenReturn(submitted);
        assertThatThrownBy(() -> adapter.snapshot(3L, java.util.Map.of()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("error.acceptance.statusTransitionInvalid");
    }

    @Test
    @DisplayName("acceptance.cancel: snapshotは金額・状態を載せ、承認適用で applyCancellation を呼ぶ")
    void snapshotAndApply() {
        when(mapper.selectByIdForUpdate(1L)).thenReturn(acceptance(1L));
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
