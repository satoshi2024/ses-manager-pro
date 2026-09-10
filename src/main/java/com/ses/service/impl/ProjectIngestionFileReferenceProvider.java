package com.ses.service.impl;

import com.ses.mapper.ProjectIngestionMapper;
import com.ses.service.FileReferenceProvider;
import com.ses.service.accounting.AccountingTenantContextHolder;
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
        String tenantId = AccountingTenantContextHolder.requireTenantContext();
        List<String> storedNames = projectIngestionMapper.selectAllStoredFileNamesForTenant(tenantId);
        return storedNames.stream()
                .collect(Collectors.toSet());
    }
}
