package com.ses.service.impl;

import com.ses.mapper.EngineerMapper;
import com.ses.service.FileReferenceProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

/**
 * 要員顔写真の参照ファイル名を提供するプロバイダー。
 * t_engineer.photo_url を孤児清理から保護する。
 */
@Service
@RequiredArgsConstructor
public class EngineerFileReferenceProvider implements FileReferenceProvider {

    private final EngineerMapper engineerMapper;

    @Override
    public Set<String> referencedFileNames() {
        Set<String> refs = new HashSet<>();
        for (String photoUrl : engineerMapper.selectAllPhotoUrls()) {
            if (photoUrl != null && !photoUrl.isBlank()) {
                refs.add(photoUrl);
            }
        }
        return refs;
    }
}
