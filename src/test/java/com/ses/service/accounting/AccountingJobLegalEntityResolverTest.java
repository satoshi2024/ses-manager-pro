package com.ses.service.accounting;

import com.ses.common.exception.BusinessException;
import com.ses.entity.BpPayment;
import com.ses.entity.Contract;
import com.ses.entity.Engineer;
import com.ses.entity.ExpenseRequest;
import com.ses.entity.IntegrationConnection;
import com.ses.entity.Invoice;
import com.ses.entity.WorkRecord;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.WorkRecordMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * NF09: 会計連携ジョブの法人は送信対象レコードから決める。
 * 全社共通接続（法人NULL）はどの法人のレコードも送れ、法人に束縛された接続は同じ法人のレコードだけを送れる。
 */
class AccountingJobLegalEntityResolverTest {

    private final WorkRecordMapper workRecordMapper = mock(WorkRecordMapper.class);
    private final ContractMapper contractMapper = mock(ContractMapper.class);
    private final EngineerMapper engineerMapper = mock(EngineerMapper.class);
    private final AccountingJobLegalEntityResolver resolver =
            new AccountingJobLegalEntityResolver(workRecordMapper, contractMapper, engineerMapper);

    @BeforeEach
    void setTenant() {
        AccountingTenantContextHolder.setTenantId("default");
    }

    @AfterEach
    void clearTenant() {
        AccountingTenantContextHolder.clear();
    }

    private static IntegrationConnection connection(Long legalEntityId) {
        IntegrationConnection c = new IntegrationConnection();
        c.setId(1L);
        c.setLegalEntityId(legalEntityId);
        return c;
    }

    @Test
    void 全社共通接続ではレコードの法人をジョブへ刻印する() {
        assertEquals(7L, resolver.require(connection(null), 7L));
        assertEquals("OK", resolver.check(connection(null), 7L).status());
    }

    @Test
    void レコードの法人が未設定なら送信させない() {
        assertEquals("MISSING", resolver.check(connection(null), null).status());
        BusinessException ex = assertThrows(BusinessException.class, () -> resolver.require(connection(null), null));
        assertEquals("error.accounting.legalEntityMissing", ex.getMessage());
        assertEquals(409, ex.getCode());
    }

    @Test
    void 法人に束縛された接続は別法人のレコードを送らない() {
        assertEquals("MISMATCH", resolver.check(connection(3L), 7L).status());
        BusinessException ex = assertThrows(BusinessException.class, () -> resolver.require(connection(3L), 7L));
        assertEquals("error.accounting.legalEntityMismatch", ex.getMessage());
        assertEquals(7L, resolver.require(connection(7L), 7L));
    }

    @Test
    void 請求書は請求書の法人() {
        Invoice invoice = new Invoice();
        invoice.setLegalEntityId(5L);
        assertEquals(5L, resolver.ofInvoice(invoice));
    }

    @Test
    void BP支払は工数実績の契約の法人() {
        BpPayment bp = new BpPayment();
        bp.setWorkRecordId(11L);
        WorkRecord wr = new WorkRecord();
        wr.setContractId(21L);
        Contract contract = new Contract();
        contract.setLegalEntityId(6L);
        when(workRecordMapper.selectByIdForTenant(11L, "default")).thenReturn(wr);
        when(contractMapper.selectByIdForTenant(21L, "default")).thenReturn(contract);

        assertEquals(6L, resolver.ofBpPayment(bp));
    }

    @Test
    void BP支払の工数実績や契約が辿れなければ未設定() {
        BpPayment noWorkRecord = new BpPayment();
        assertNull(resolver.ofBpPayment(noWorkRecord));

        BpPayment bp = new BpPayment();
        bp.setWorkRecordId(12L);
        when(workRecordMapper.selectByIdForTenant(12L, "default")).thenReturn(null);
        assertNull(resolver.ofBpPayment(bp));
    }

    @Test
    void 要員経費は要員の法人() {
        ExpenseRequest expense = new ExpenseRequest();
        expense.setEngineerId(31L);
        Engineer engineer = new Engineer();
        engineer.setLegalEntityId(8L);
        when(engineerMapper.selectByIdForTenant(31L, "default")).thenReturn(engineer);

        assertEquals(8L, resolver.ofExpense(expense));
    }
}
