package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.entity.BpAvailabilityIngestion;
import com.ses.mapper.BpAvailabilityIngestionMapper;
import com.ses.service.FileReferenceProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 取込中の要員空き状況メール原本ファイルをクリーンアップ対象外にするための参照プロバイダ。
 */
@Component
@RequiredArgsConstructor
public class BpAvailabilityFileReferenceProvider implements FileReferenceProvider {

    private final BpAvailabilityIngestionMapper bpAvailabilityIngestionMapper;

    @Override
    public Set<String> referencedFileNames() {
        LambdaQueryWrapper<BpAvailabilityIngestion> wrapper = new LambdaQueryWrapper<>();
        wrapper.ne(BpAvailabilityIngestion::getFileExt, "PASTE")
               .isNotNull(BpAvailabilityIngestion::getStoredFileName);

        List<BpAvailabilityIngestion> ingestions = bpAvailabilityIngestionMapper.selectList(wrapper);
        return ingestions.stream()
                .map(BpAvailabilityIngestion::getStoredFileName)
                .collect(Collectors.toSet());
    }
}
