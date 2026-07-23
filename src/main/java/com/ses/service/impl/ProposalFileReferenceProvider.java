package com.ses.service.impl;

import com.ses.mapper.ProposalMapper;
import com.ses.service.FileReferenceProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

/**
 * 提案スキルシートの参照ファイル名を提供するプロバイダー。
 * t_proposal.skill_sheet_path を孤児清理から保護する。
 */
@Service
@RequiredArgsConstructor
public class ProposalFileReferenceProvider implements FileReferenceProvider {

    private final ProposalMapper proposalMapper;

    @Override
    public Set<String> referencedFileNames() {
        Set<String> refs = new HashSet<>();
        for (String path : proposalMapper.selectAllSkillSheetPaths()) {
            if (path != null && !path.isBlank()) {
                refs.add(path);
            }
        }
        return refs;
    }
}
