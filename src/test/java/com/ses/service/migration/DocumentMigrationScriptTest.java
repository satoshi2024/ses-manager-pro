package com.ses.service.migration;

import com.ses.dto.document.DocumentRegisterRequest;
import com.ses.entity.ContractDocument;
import com.ses.entity.Proposal;
import com.ses.entity.ResumeIngestion;
import com.ses.mapper.ContractDocumentMapper;
import com.ses.mapper.ProposalMapper;
import com.ses.mapper.ResumeIngestionMapper;
import com.ses.service.DocumentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Task M DocumentMigrationScript テスト。
 */
@ExtendWith(MockitoExtension.class)
class DocumentMigrationScriptTest {

    @Mock DocumentService documentService;
    @Mock ResumeIngestionMapper resumeIngestionMapper;
    @Mock ProposalMapper proposalMapper;
    @Mock ContractDocumentMapper contractDocumentMapper;

    @InjectMocks DocumentMigrationScript migrationScript;

    @TempDir Path tempDir;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(migrationScript, "uploadBase", tempDir.toString());
    }

    @Test
    void migrateResumes_migratesExistingFile() throws Exception {
        Path resumesDir = Files.createDirectories(tempDir.resolve("resumes"));
        Files.writeString(resumesDir.resolve("resume1.pdf"), "content");

        ResumeIngestion r = new ResumeIngestion();
        r.setId(10L);
        r.setStoredFileName("resume1.pdf");
        r.setOriginalFileName("山田太郎_職務経歴書.pdf");
        r.setStatus("未変換");

        when(resumeIngestionMapper.selectList(any())).thenReturn(List.of(r));

        int count = migrationScript.migrateResumes();
        assertEquals(1, count);
        verify(documentService, times(1)).registerReceived(any(DocumentRegisterRequest.class), any(InputStream.class));
    }
}
