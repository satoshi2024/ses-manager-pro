package com.ses.integration;

import com.ses.common.exception.BusinessException;
import com.ses.entity.BpPayment;
import com.ses.entity.ExpenseRequest;
import com.ses.service.accounting.PurchaseExpensePaymentIntegrationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;

/**
 * R1-P1-08: NULL金額・NULL日付の fail-closed ガード専用テスト。
 * DB カラムが NOT NULL のため通常は NULL 行が存在し得ないが、防御的ガードとして
 * mapper を spy して NULL 行相当を返し、enqueue が拒否されることを検証する。
 */
@SpringBootTest
@ActiveProfiles("test")
class AccountingNullGuardTest {

    @Autowired
    private PurchaseExpensePaymentIntegrationService purchaseIntegrationService;

    @SpyBean
    private com.ses.mapper.BpPaymentMapper bpPaymentMapper;

    @SpyBean
    private com.ses.mapper.ExpenseRequestMapper expenseRequestMapper;

    @Test
    @DisplayName("NULL金額BP支払は enqueue 時に拒否される (R1-P1-08)")
    void bpNullAmount_rejected() {
        BpPayment nullAmountRow = new BpPayment();
        nullAmountRow.setId(1L);
        nullAmountRow.setWorkRecordId(1L);
        nullAmountRow.setBpCompanyId(1L);
        nullAmountRow.setPayeeCompanyName("NULL金額BP");
        nullAmountRow.setStatus("未払");
        doReturn(nullAmountRow).when(bpPaymentMapper).selectById(1L);

        assertThatThrownBy(() -> purchaseIntegrationService.triggerBpPurchaseSync(1L, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("金額は必須");
    }

    @Test
    @DisplayName("NULL金額・NULL発生日の経費申請は enqueue 時に拒否される (R1-P1-08)")
    void expenseNullAmountAndDate_rejected() {
        ExpenseRequest nullAmount = new ExpenseRequest();
        nullAmount.setId(2L);
        nullAmount.setEngineerId(1L);
        nullAmount.setExpenseNo("EX-NULL-AMOUNT");
        nullAmount.setCategory("交通費");
        nullAmount.setStatus("承認済");
        doReturn(nullAmount).when(expenseRequestMapper).selectById(2L);

        assertThatThrownBy(() -> purchaseIntegrationService.triggerExpenseSync(2L, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("金額は必須");

        ExpenseRequest nullDate = new ExpenseRequest();
        nullDate.setId(3L);
        nullDate.setEngineerId(1L);
        nullDate.setExpenseNo("EX-NULL-DATE");
        nullDate.setCategory("交通費");
        nullDate.setAmount(new java.math.BigDecimal("1000"));
        nullDate.setStatus("承認済");
        doReturn(nullDate).when(expenseRequestMapper).selectById(3L);

        assertThatThrownBy(() -> purchaseIntegrationService.triggerExpenseSync(3L, 1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("発生日は必須");
    }
}
