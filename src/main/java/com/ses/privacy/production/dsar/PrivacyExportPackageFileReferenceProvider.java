package com.ses.privacy.production.dsar;

import com.ses.mapper.privacy.PrivacyExportPackageMapper;
import com.ses.service.FileReferenceProvider;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * DSAR の開示 package の実体を孤児清掃（{@code FileCleanupService}）から守る（NF07-A2b、production-bridge-phased-plan.md §3.5.2）。
 *
 * <p>PURGED でない package の保存名を全 tenant について「参照あり」として返す（清掃は upload root を tenant に関係なく共有するため）。
 * PURGED にした package は返さない（交付記録・期限超過で消した実体を守り続けない）。本 provider は清掃の参照集合にだけ使い、
 * ダウンロードの許可には使わない（取得は専用ダウンロードだけ。汎用 file 経路は {@code FileScopeValidationService} が拒否する）。
 * 照会に失敗した場合は例外をそのまま返し、清掃のその回を中止させる（{@code FileCleanupServiceImpl} は参照の集約を削除の前に行う）。
 */
public class PrivacyExportPackageFileReferenceProvider implements FileReferenceProvider {

    private final PrivacyExportPackageMapper packageMapper;

    public PrivacyExportPackageFileReferenceProvider(PrivacyExportPackageMapper packageMapper) {
        this.packageMapper = Objects.requireNonNull(packageMapper, "packageMapper");
    }

    @Override
    public Set<String> referencedFileNames() {
        List<String> names = packageMapper.selectLiveStoredNames();
        Set<String> result = new LinkedHashSet<>();
        for (String name : names == null ? List.<String>of() : names) {
            if (name != null && !name.isBlank()) {
                result.add(name.trim());
            }
        }
        return Collections.unmodifiableSet(result);
    }
}
