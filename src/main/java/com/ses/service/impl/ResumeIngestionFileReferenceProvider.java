package com.ses.service.impl;

import com.ses.mapper.ResumeIngestionMapper;
import com.ses.service.FileReferenceProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

/**
 * スキルシート取込原本の参照ファイル名を提供するプロバイダー。
 * 却下以外のジョブの原本ファイルを孤児清理から保護する。
 */
@Service
@RequiredArgsConstructor
public class ResumeIngestionFileReferenceProvider implements FileReferenceProvider {

    private final ResumeIngestionMapper resumeIngestionMapper;

    @Override
    public Set<String> referencedFileNames() {
        Set<String> refs = new HashSet<>();
        for (String name : resumeIngestionMapper.selectAllStoredFileNames()) {
            if (name != null && !name.isBlank()) {
                refs.add(name);
            }
        }
        return refs;
    }
}
