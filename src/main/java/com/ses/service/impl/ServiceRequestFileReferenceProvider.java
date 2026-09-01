package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.entity.DocumentVersion;
import com.ses.entity.ServiceAttachmentLink;
import com.ses.mapper.DocumentVersionMapper;
import com.ses.mapper.ServiceAttachmentLinkMapper;
import com.ses.service.FileReferenceProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * サービスリクエスト添付ファイルの storage key を孤児清理から保護するプロバイダー
 */
@Service
@RequiredArgsConstructor
public class ServiceRequestFileReferenceProvider implements FileReferenceProvider {

    private final ServiceAttachmentLinkMapper serviceAttachmentLinkMapper;
    private final DocumentVersionMapper documentVersionMapper;

    @Override
    public Set<String> referencedFileNames() {
        Set<String> refs = new HashSet<>();
        List<ServiceAttachmentLink> links = serviceAttachmentLinkMapper.selectList(
                new LambdaQueryWrapper<ServiceAttachmentLink>().select(ServiceAttachmentLink::getDocumentId));
        if (links.isEmpty()) {
            return refs;
        }

        Set<Long> docIds = new HashSet<>();
        for (ServiceAttachmentLink link : links) {
            if (link.getDocumentId() != null) {
                docIds.add(link.getDocumentId());
            }
        }

        if (docIds.isEmpty()) {
            return refs;
        }

        List<DocumentVersion> versions = documentVersionMapper.selectList(
                new LambdaQueryWrapper<DocumentVersion>()
                        .in(DocumentVersion::getDocumentId, docIds)
                        .select(DocumentVersion::getStorageKey));

        for (DocumentVersion v : versions) {
            if (v.getStorageKey() != null && !v.getStorageKey().isBlank()) {
                refs.add(v.getStorageKey());
                int idx = v.getStorageKey().lastIndexOf('/');
                if (idx >= 0) {
                    refs.add(v.getStorageKey().substring(idx + 1));
                }
            }
        }
        return refs;
    }
}
