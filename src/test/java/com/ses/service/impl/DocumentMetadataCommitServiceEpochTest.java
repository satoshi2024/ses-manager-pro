package com.ses.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.ses.entity.Document;
import com.ses.entity.DocumentVersion;
import com.ses.mapper.DocumentAccessLogMapper;
import com.ses.mapper.DocumentHashClaimMapper;
import com.ses.mapper.DocumentLinkMapper;
import com.ses.mapper.DocumentMapper;
import com.ses.mapper.DocumentVersionMapper;
import com.ses.service.security.epoch.AuthorizationEpochAnchor;
import com.ses.service.security.epoch.AuthorizationEpochFence;
import com.ses.service.storage.DocumentStorage;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * NF01細粒度epoch計画 §4.3（commitNew・commitVersion）: 版追加は文書行を先にX lockしてから版を挿入し
 * （lock順 15 → 16）、利用可否を変えないためepochを進めない。新規文書の登録はepochに触れない（DEFAULT 0）。
 */
class DocumentMetadataCommitServiceEpochTest {

    private DocumentMapper documentMapper;
    private DocumentVersionMapper versionMapper;
    private DocumentLinkMapper linkMapper;
    private AuthorizationEpochFence fence;
    private DocumentMetadataCommitService service;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, Document.class);
        TableInfoHelper.initTableInfo(assistant, com.ses.entity.DocumentLink.class);
    }

    @BeforeEach
    void setUp() {
        documentMapper = mock(DocumentMapper.class);
        versionMapper = mock(DocumentVersionMapper.class);
        linkMapper = mock(DocumentLinkMapper.class);
        fence = mock(AuthorizationEpochFence.class);
        service = new DocumentMetadataCommitService(documentMapper, versionMapper, linkMapper,
                mock(DocumentAccessLogMapper.class), mock(DocumentHashClaimMapper.class), mock(DocumentStorage.class), fence);
    }

    @Test
    void 版追加は文書行をlockしてから版を挿入しAMENDEDにしてもepochを進めない() {
        Document document = new Document();
        document.setId(5L);
        document.setTenantId("tenant-a");
        document.setStatus("CONFIRMED");
        document.setVersion(2L);
        when(documentMapper.update(any(), any())).thenReturn(1);

        service.commitVersion(document, new DocumentVersion());

        InOrder order = inOrder(fence, versionMapper, documentMapper);
        order.verify(fence).lockForUpdate(AuthorizationEpochAnchor.document("tenant-a", 5L));
        order.verify(versionMapper).insert(any(DocumentVersion.class));
        order.verify(documentMapper).update(any(), any());
        verify(fence, never()).advance(any());
        verify(fence, never()).advanceIfChanged(any(), any(), any());
    }

    @Test
    void 新規文書の登録はepochに触れない() {
        Document document = new Document();
        document.setTenantId("tenant-a");
        document.setDocumentType("SKILL_SHEET");
        DocumentVersion version = new DocumentVersion();

        service.commitNew(document, version, "ENGINEER", 7L);

        verify(documentMapper).insert(document);
        verify(linkMapper).insert(any(com.ses.entity.DocumentLink.class));
        verifyNoInteractions(fence);
    }
}
