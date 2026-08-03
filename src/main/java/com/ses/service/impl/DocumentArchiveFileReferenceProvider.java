package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.entity.DocumentVersion;
import com.ses.mapper.DocumentVersionMapper;
import com.ses.service.FileReferenceProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 文書台帳版の storage key を孤児清理から保護するプロバイダー（P2-03）。
 */
@Service
@RequiredArgsConstructor
public class DocumentArchiveFileReferenceProvider implements FileReferenceProvider {

    private final DocumentVersionMapper documentVersionMapper;

    @Override
    public Set<String> referencedFileNames() {
        Set<String> refs = new HashSet<>();
        // 全カラムではなく storage_key のみを取得して保護集合へ追加
        List<DocumentVersion> list = documentVersionMapper.selectList(
                new LambdaQueryWrapper<DocumentVersion>().select(DocumentVersion::getStorageKey));
        for (DocumentVersion v : list) {
            if (v.getStorageKey() != null && !v.getStorageKey().isBlank()) {
                refs.add(v.getStorageKey());
                // パス切り離し名（ファイル名のみ）も念のため追加
                int idx = v.getStorageKey().lastIndexOf('/');
                if (idx >= 0) {
                    refs.add(v.getStorageKey().substring(idx + 1));
                }
            }
        }
        return refs;
    }
}
