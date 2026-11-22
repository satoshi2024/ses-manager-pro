package com.ses.controller.api;

import com.ses.common.enums.FileKind;
import com.ses.common.exception.BusinessException;
import com.ses.dto.file.StoredFile;
import com.ses.service.FileStorageService;
import com.ses.service.security.impl.FileScopeValidationService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 汎用の upload（{@code POST /api/files}）が、専用の経路だけが保存する種別を保存の前に拒否することを固定する（FE-A02。
 * server 生成の種別の拒否の試験が無かった穴も埋める。CLAUDE.md の server 生成の種別の (a)）。
 */
class FileApiControllerTest {

    private final FileStorageService storage = mock(FileStorageService.class);
    private final FileApiController controller = new FileApiController(storage, mock(FileScopeValidationService.class));
    private final MockMultipartFile pdf = new MockMultipartFile("file", "signoff.pdf", "application/pdf",
            "%PDF-1.7\n1 0 obj<<>>endobj\n%%EOF\n".getBytes(StandardCharsets.US_ASCII));

    @Test
    void 機能の開放の署名の文書は汎用のuploadで400になり保存しない() {
        BusinessException rejected = catchThrowableOfType(() -> controller.upload(pdf, FileKind.FEATURE_SIGN_OFF),
                BusinessException.class);

        assertThat(rejected).isNotNull();
        assertThat(rejected.getCode()).isEqualTo(400);
        assertThat(rejected.getMessageKey()).isEqualTo("error.file.kindNotAllowed");
        verify(storage, never()).store(any(org.springframework.web.multipart.MultipartFile.class), any());
    }

    @Test
    void server生成の種別は汎用のuploadで403になり保存しない() {
        for (FileKind kind : Arrays.stream(FileKind.values()).filter(FileKind::isServerGeneratedOnly).toList()) {
            BusinessException rejected = catchThrowableOfType(() -> controller.upload(pdf, kind), BusinessException.class);
            assertThat(rejected).as(kind.name()).isNotNull();
            assertThat(rejected.getCode()).as(kind.name()).isEqualTo(403);
        }
        verify(storage, never()).store(any(org.springframework.web.multipart.MultipartFile.class), any());
    }

    @Test
    void 専用の経路だけの種別でなければ汎用のuploadへ渡す() {
        StoredFile stored = new StoredFile("stored.pdf", "signoff.pdf", 10);
        when(storage.store(pdf, FileKind.SKILL_SHEET)).thenReturn(stored);

        assertThat(controller.upload(pdf, FileKind.SKILL_SHEET).getData()).isSameAs(stored);
        for (FileKind kind : FileKind.values()) {
            assertThat(kind.isDedicatedPathOnly()).as(kind.name())
                    .isEqualTo(kind == FileKind.PRIVACY_EXPORT || kind == FileKind.FEATURE_SIGN_OFF);
        }
    }
}
