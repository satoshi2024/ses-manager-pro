package com.ses.order;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.ses.common.constant.StatusConstants;
import com.ses.common.exception.BusinessException;
import com.ses.entity.SalesOrder;
import com.ses.entity.SalesOrderLine;
import com.ses.mapper.ApprovalRequestMapper;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.CustomerContactMapper;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.QuotationMapper;
import com.ses.mapper.SalesOrderLineMapper;
import com.ses.mapper.SalesOrderMapper;
import com.ses.service.ContractService;
import com.ses.service.impl.SalesOrderServiceImpl;
import com.ses.service.security.DataScopeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** T054定向テスト: 注文の状態機械・採番・金額集計・PO重複（L1）。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SalesOrderServiceImplTest {

    @Mock private SalesOrderMapper salesOrderMapper;
    @Mock private SalesOrderLineMapper lineMapper;
    @Mock private QuotationMapper quotationMapper;
    @Mock private ProjectMapper projectMapper;
    @Mock private EngineerMapper engineerMapper;
    @Mock private CustomerMapper customerMapper;
    @Mock private CustomerContactMapper customerContactMapper;
    @Mock private ContractMapper contractMapper;
    @Mock private ApprovalRequestMapper approvalRequestMapper;
    @Mock private ContractService contractService;
    @Mock private DataScopeService dataScopeService;

    @InjectMocks
    private SalesOrderServiceImpl service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "baseMapper", salesOrderMapper);
        lenient().when(dataScopeService.isScoped()).thenReturn(false);
    }

    private SalesOrder draft(Long id, String status) {
        SalesOrder order = new SalesOrder();
        order.setId(id);
        order.setCustomerId(10L);
        order.setStatus(status);
        order.setVersion(0);
        lenient().when(salesOrderMapper.selectById(id)).thenReturn(order);
        lenient().when(salesOrderMapper.selectByIdForUpdate(id)).thenReturn(order);
        return order;
    }

    @Test
    @DisplayName("状態遷移: 下書き→受領確認→注文請提出→契約化→完了")
    void happyPathTransitions() {
        SalesOrder o1 = draft(1L, StatusConstants.ORDER_DRAFT);
        SalesOrder o2 = draft(2L, StatusConstants.ORDER_RECEIVED);
        SalesOrder o3 = draft(3L, StatusConstants.ORDER_ACK_SUBMITTED);
        SalesOrder o4 = draft(4L, StatusConstants.ORDER_CONTRACTED);
        when(lineMapper.selectList(any(Wrapper.class))).thenReturn(List.of(line(100L, "500000"), line(101L, "600000")));

        assertThat(service.changeStatus(1L, StatusConstants.ORDER_RECEIVED).getStatus())
                .isEqualTo(StatusConstants.ORDER_RECEIVED);
        assertThat(service.changeStatus(2L, StatusConstants.ORDER_ACK_SUBMITTED).getStatus())
                .isEqualTo(StatusConstants.ORDER_ACK_SUBMITTED);
        assertThat(service.changeStatus(3L, StatusConstants.ORDER_CONTRACTED).getStatus())
                .isEqualTo(StatusConstants.ORDER_CONTRACTED);
        assertThat(service.changeStatus(4L, StatusConstants.ORDER_COMPLETED).getStatus())
                .isEqualTo(StatusConstants.ORDER_COMPLETED);
    }

    @Test
    @DisplayName("状態遷移: 許可外遷移と契約化→取消（承認必須）は拒否する")
    void invalidTransitionsRejected() {
        SalesOrder d1 = draft(1L, StatusConstants.ORDER_DRAFT);
        // 下書き→契約化は許可外
        assertThatThrownBy(() -> service.changeStatus(1L, StatusConstants.ORDER_CONTRACTED))
                .isInstanceOf(BusinessException.class);

        SalesOrder d2 = draft(2L, StatusConstants.ORDER_DRAFT);
        // 下書き→取消は許可
        assertThat(service.changeStatus(2L, StatusConstants.ORDER_CANCELLED).getStatus())
                .isEqualTo(StatusConstants.ORDER_CANCELLED);

        SalesOrder d3 = draft(3L, StatusConstants.ORDER_CONTRACTED);
        // 契約化→取消は直接拒否（承認必須）
        assertThatThrownBy(() -> service.changeStatus(3L, StatusConstants.ORDER_CANCELLED))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("error.order.cancelRequiresApproval");
    }

    @Test
    @DisplayName("受領確認への遷移で金額snapshotが明細合計に固定される")
    void totalAmountSnapshotFixedOnReceipt() {
        SalesOrder order = draft(5L, StatusConstants.ORDER_DRAFT);
        when(salesOrderMapper.selectByIdForUpdate(5L)).thenReturn(order);
        when(lineMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(line(200L, "500000"), line(201L, "600000")));

        service.changeStatus(5L, StatusConstants.ORDER_RECEIVED);

        assertThat(order.getTotalAmountSnapshot()).isEqualByComparingTo("1100000");
        assertThat(order.getStatus()).isEqualTo(StatusConstants.ORDER_RECEIVED);
    }

    @Test
    @DisplayName("注文番号は連番採番される（O-yyyyMM-NNNN）")
    void orderNumberGeneration() {
        String prefix = "O-202608-";
        when(salesOrderMapper.selectMaxOrderNo(eq(prefix))).thenReturn(prefix + "0003");
        assertThat(service.generateOrderNo(LocalDate.of(2026, 8, 5))).isEqualTo(prefix + "0004");

        when(salesOrderMapper.selectMaxOrderNo(eq(prefix))).thenReturn(null);
        assertThat(service.generateOrderNo(LocalDate.of(2026, 8, 5))).isEqualTo(prefix + "0001");
    }

    @Test
    @DisplayName("PO重複判定: 同一顧客×同一POはtrue、別顧客/別POはfalse")
    void poDuplicateDetection() {
        when(salesOrderMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        assertThat(service.isCustomerPoDuplicate(10L, "PO-001")).isTrue();
        when(salesOrderMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        assertThat(service.isCustomerPoDuplicate(11L, "PO-001")).isFalse();
        assertThat(service.isCustomerPoDuplicate(10L, "   ")).isFalse();
    }

    @Test
    @DisplayName("R09-P2-06: scope外顧客のPO重複照会は拒否される（IDOR防止）")
    void poDuplicateRejectsScopeOutsideCustomer() {
        org.mockito.Mockito.doThrow(new BusinessException(404, "error.scope.notFound"))
                .when(dataScopeService).assertAllowedCustomer(999L);
        assertThatThrownBy(() -> service.isCustomerPoDuplicate(999L, "PO-001"))
                .isInstanceOf(BusinessException.class);
    }

    private SalesOrderLine line(Long id, String amount) {
        SalesOrderLine line = new SalesOrderLine();
        line.setId(id);
        line.setAmount(new BigDecimal(amount));
        return line;
    }
}
