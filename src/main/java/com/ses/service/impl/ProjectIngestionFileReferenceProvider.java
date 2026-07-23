package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.entity.ProjectIngestion;
import com.ses.mapper.ProjectIngestionMapper;
import com.ses.service.FileReferenceProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 取込中の案件メール原本ファイルをクリーンアップ対象外にするための参照プロバイダ。
 */
@Component
@RequiredArgsConstructor
public class ProjectIngestionFileReferenceProvider implements FileReferenceProvider {

    private final ProjectIngestionMapper projectIngestionMapper;

    @Override
    public Set<String> referencedFileNames() {
        // EML形式でアップロードされ、storedFileName が設定されているものを全件取得
        LambdaQueryWrapper<ProjectIngestion> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ProjectIngestion::getSourceType, "EML")
               .isNotNull(ProjectIngestion::getStoredFileName);

        List<ProjectIngestion> ingestions = projectIngestionMapper.selectList(wrapper);
        return ingestions.stream()
                .map(ProjectIngestion::getStoredFileName)
                .collect(Collectors.toSet());
    }
}
