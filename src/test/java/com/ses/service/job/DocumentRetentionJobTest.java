package com.ses.service.job;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.Document;
import com.ses.mapper.DocumentMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.document.DocumentEligibilityService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * U9（NF01細粒度epoch計画 §4.3 DocumentRetentionJob・§6.2）: 保存期限jobがtenant contextを持たずに、各文書行自身の
 * tenantで {@link DocumentEligibilityService} を文書ごとに呼び、1文書の失敗で残りを止めないことを確かめる。
 */
class DocumentRetentionJobTest {

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), Document.class);
    }

    @Test
    void 保存期限jobは文書自身のtenantで文書ごとに廃棄候補を登録し失敗しても次へ進む() {
        AccountingTenantContextHolder.clear();
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        DocumentEligibilityService eligibility = mock(DocumentEligibilityService.class);
        when(documentMapper.selectList(any())).thenReturn(List.of(
                expired("tenant-a", 1L), expired("tenant-b", 2L), expired("tenant-c", 3L)));
        when(eligibility.registerRetentionDisposalCandidate(eq("tenant-a"), eq(1L), any()))
                .thenThrow(BusinessException.of(409, "error.authorization.epochUnavailable"));
        when(eligibility.registerRetentionDisposalCandidate(eq("tenant-b"), eq(2L), any())).thenReturn(true);
        when(eligibility.registerRetentionDisposalCandidate(eq("tenant-c"), eq(3L), any())).thenReturn(false);

        new DocumentRetentionJob(documentMapper, eligibility).processExpiredDocuments();

        verify(eligibility).registerRetentionDisposalCandidate(eq("tenant-a"), eq(1L), any(LocalDate.class));
        verify(eligibility).registerRetentionDisposalCandidate(eq("tenant-b"), eq(2L), any(LocalDate.class));
        verify(eligibility).registerRetentionDisposalCandidate(eq("tenant-c"), eq(3L), any(LocalDate.class));
        assertNull(AccountingTenantContextHolder.getExplicitTenantId(), "jobはtenant contextを設定しない");
    }

    private static Document expired(String tenantId, long id) {
        Document document = new Document();
        document.setId(id);
        document.setTenantId(tenantId);
        document.setStatus("CONFIRMED");
        document.setLegalHoldFlag(0);
        document.setRetentionUntil(LocalDate.now().minusDays(1));
        return document;
    }
}
