package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.entity.FileSecurityMetadata;
import com.ses.mapper.FileSecurityMetadataMapper;
import com.ses.service.FileReferenceProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

/** scan待ち・拒否・公開済みを含む有効metadataの実体を孤児清掃から保護する。 */
@Component
@RequiredArgsConstructor
public class FileSecurityMetadataReferenceProvider implements FileReferenceProvider {

    private final FileSecurityMetadataMapper metadataMapper;

    @Override
    public Set<String> referencedFileNames() {
        return metadataMapper.selectList(new LambdaQueryWrapper<FileSecurityMetadata>()
                        .select(FileSecurityMetadata::getStoredName))
                .stream()
                .map(FileSecurityMetadata::getStoredName)
                .filter(name -> name != null && !name.isBlank())
                .collect(Collectors.toSet());
    }
}
