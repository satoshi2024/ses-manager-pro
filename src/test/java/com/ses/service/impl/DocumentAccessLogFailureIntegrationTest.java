package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.dto.document.DocumentRegisterRequest;
import com.ses.entity.DocumentAccessLog;
import com.ses.mapper.DocumentAccessLogMapper;
import com.ses.mapper.DocumentMapper;
import com.ses.service.DocumentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.transaction.BeforeTransaction;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/** 文書アクセス監査ログの永続化失敗が業務transactionをrollbackすることを確認する。 */
@SpringBootTest
@ActiveProfiles("test")
@Sql("/sql/schema-document-archive-h2.sql")
@Transactional
@com.ses.test.EnableDefaultTenantTestContext
class DocumentAccessLogFailureIntegrationTest {

    @Autowired
    private DocumentService documentService;

    @Autowired
    private DocumentMapper documentMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @SpyBean
    private DocumentAccessLogMapper documentAccessLogMapper;

    @BeforeTransaction
    void prepareLegalEntityContext() {
        com.ses.test.TenantTestSecurity.ensureLegalEntity(jdbcTemplate, 1L);
    }

    @AfterEach
    void tearDown() {
        Mockito.reset(documentAccessLogMapper);
    }

    @Test
    void アクセス監査ログmapper失敗時は文書登録もrollbackする() {
        doThrow(new IllegalStateException("監査ログDB障害"))
                .when(documentAccessLogMapper).insert(any(DocumentAccessLog.class));
        TransactionTemplate isolated = new TransactionTemplate(transactionManager);
        isolated.setPropagationBehavior(Propagation.REQUIRES_NEW.value());

        assertThatThrownBy(() -> isolated.executeWithoutResult(status -> documentService.registerGenerated(
                DocumentRegisterRequest.builder()
                        .documentType("INVOICE_OUT")
                        .legalEntityId(1L)
                        .sourceType("GENERATED")
                        .businessKey("audit-failure-transaction")
                        .versionDiscriminator("v1")
                        .direction("OUTGOING")
                        .build(),
                new ByteArrayInputStream("PDF_BYTES".getBytes()))))
                .isInstanceOf(BusinessException.class)
                .hasMessage("文書アクセス監査ログの記録に失敗しました。");

        assertThat(documentMapper.selectCount(null)).isZero();
    }
}
