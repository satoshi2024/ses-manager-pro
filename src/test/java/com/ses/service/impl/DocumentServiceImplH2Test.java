package com.ses.service.impl;

import com.ses.dto.document.DocumentRegisterRequest;
import com.ses.entity.Document;
import com.ses.entity.DocumentVersion;
import com.ses.mapper.DocumentMapper;
import com.ses.mapper.DocumentVersionMapper;
import com.ses.service.DocumentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * H2インメモリDBを用いた実SQL往復・UNIQUE制約・CRUD統合テスト。
 */
@SpringBootTest
@ActiveProfiles("test")
@Sql("/sql/schema-document-archive-h2.sql")
@Transactional
class DocumentServiceImplH2Test {

    @Autowired DocumentService documentService;
    @Autowired DocumentMapper documentMapper;
    @Autowired DocumentVersionMapper documentVersionMapper;

    @BeforeEach
    void setUp() {
        var principal = User.withUsername("1").password("").authorities("ROLE_管理者").build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @Test
    void H2DB_registerAndIdempotentCheck_persistsAndReuses() {
        var req = DocumentRegisterRequest.builder()
                .documentType("INVOICE_OUT")
                .sourceType("GENERATED")
                .businessKey("INV:2026-001")
                .versionDiscriminator("v1")
                .title("テスト請求書")
                .direction("OUTGOING")
                .originalName("invoice_001.pdf")
                .build();

        // 1回目の登録
        Document doc1 = documentService.registerGenerated(req, new ByteArrayInputStream("PDF_BYTES".getBytes()));
        assertNotNull(doc1.getId());

        // H2 DBへの実挿入アサート
        Document dbDoc = documentMapper.selectById(doc1.getId());
        assertNotNull(dbDoc);
        assertEquals("DRAFT", dbDoc.getStatus());
        assertEquals("default", dbDoc.getTenantId());

        // 2回目の同一キー登録（冪等性）
        Document doc2 = documentService.registerGenerated(req, new ByteArrayInputStream("PDF_BYTES".getBytes()));
        assertEquals(doc1.getId(), doc2.getId(), "同一冪等キーの場合は同一のDocumentが返されるべき");

        // 版が重複挿入されていないこと
        Long count = documentVersionMapper.selectCount(null);
        assertEquals(1L, count, "冪等制御により1件のみ登録されること");

        DocumentVersion version = documentVersionMapper.findLatestByDocumentId(doc1.getId());
        assertNotNull(version);
        assertEquals("default", version.getTenantId());
        assertEquals("CLEAN", version.getScanStatus());
    }

    @Test
    void H2DB_confirm_updatesStatusAndVersion() {
        var req = DocumentRegisterRequest.builder()
                .documentType("INVOICE_OUT")
                .sourceType("GENERATED")
                .businessKey("INV:2026-002")
                .versionDiscriminator("v1")
                .direction("OUTGOING")
                .build();

        Document doc = documentService.registerGenerated(req, new ByteArrayInputStream("PDF_BYTES".getBytes()));

        documentService.confirm(doc.getId());

        Document updated = documentMapper.selectById(doc.getId());
        assertEquals("CONFIRMED", updated.getStatus());
        assertEquals(2L, updated.getVersion());
    }
}
