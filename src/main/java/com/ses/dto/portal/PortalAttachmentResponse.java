package com.ses.dto.portal;

import com.ses.entity.ServiceAttachmentLink;

import java.time.LocalDateTime;

/** 顧客ポータル添付の公開可能な表示項目だけを返すresponse projection。 */
public record PortalAttachmentResponse(Long attachmentId, String fileName, Long fileSize,
                                       LocalDateTime createdAt) {

    public static PortalAttachmentResponse from(ServiceAttachmentLink link) {
        if (link == null) {
            return null;
        }
        return new PortalAttachmentResponse(link.getId(), link.getFileName(), link.getFileSize(), link.getCreatedAt());
    }
}
