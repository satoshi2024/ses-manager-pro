package com.ses.service.impl;

import com.ses.mapper.DocumentVersionMapper;
import com.ses.service.FileReferenceProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

/**
 * 文書台帳版のstorage keyを孤児清理から保護するプロバイダー。
 * t_document_version.storage_key を FileCleanupService の参照集合に加える。
 *
 * <p>新機能がファイルを追加した際の FileReferenceProvider 登録忘れは、
 * 正当なファイルが孤児とみなされ削除される危険がある（FileScopeValidationService のコメント参照）。</p>
 */
@Service
@RequiredArgsConstructor
public class DocumentArchiveFileReferenceProvider implements FileReferenceProvider {

    private final DocumentVersionMapper documentVersionMapper;

    @Override
    public Set<String> referencedFileNames() {
        Set<String> refs = new HashSet<>();
        // storage_key は推測困難なUUIDベースのパス。削除保護のためset全件を返す。
        documentVersionMapper.selectList(null).forEach(v -> {
            if (v.getStorageKey() != null) {
                refs.add(v.getStorageKey());
            }
        });
        return refs;
    }
}
