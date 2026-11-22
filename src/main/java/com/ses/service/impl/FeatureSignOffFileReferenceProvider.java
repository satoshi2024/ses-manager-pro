package com.ses.service.impl;

import com.ses.mapper.FeatureSignOffMapper;
import com.ses.service.FileReferenceProvider;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 機能の開放の署名の文書を孤児清掃（{@code FileCleanupService}）から守る（feature-enablement-console design §6.2 の1）。
 *
 * <p>消去（{@code PURGED}）していない署名の保存名を全 tenant について「参照あり」として返す（清掃は upload root を tenant に関係なく
 * 共有するため）。却下・取消し・差替え・失効の後も、消去の期日（3年。design §6.6）までは文書を守る。{@code PURGED} にした署名は保存名が
 * NULL のため返さない。本 provider は清掃の参照集合にだけ使い、取得の許可には使わない（取得は専用の経路だけ。汎用 file 経路は
 * {@code FileScopeValidationService} と {@code FileStorageService} の種別の検査が拒否する）。照会に失敗した場合は例外をそのまま返し、
 * 清掃のその回を中止させる（{@code FileCleanupServiceImpl} は参照の集約を削除の前に行う）。
 */
@Service
public class FeatureSignOffFileReferenceProvider implements FileReferenceProvider {

    private final FeatureSignOffMapper signOffMapper;

    public FeatureSignOffFileReferenceProvider(FeatureSignOffMapper signOffMapper) {
        this.signOffMapper = Objects.requireNonNull(signOffMapper, "signOffMapper");
    }

    @Override
    public Set<String> referencedFileNames() {
        List<String> names = signOffMapper.selectLiveStoredNames();
        Set<String> result = new LinkedHashSet<>();
        for (String name : names == null ? List.<String>of() : names) {
            if (name != null && !name.isBlank()) {
                result.add(name.trim());
            }
        }
        return Collections.unmodifiableSet(result);
    }
}
