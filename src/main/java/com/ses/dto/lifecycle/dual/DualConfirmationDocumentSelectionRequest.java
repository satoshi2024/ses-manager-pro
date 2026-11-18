package com.ses.dto.lifecycle.dual;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 二者確認の提出で選ぶ文書版（NF01細粒度epoch計画 §5.2 A3/A7 の {@code documentSelections} の要素）。
 *
 * <p>clientが選べるのは文書IDと版IDだけで、hash・storage key・title・scan状態は受け付けない。Spring Bootの既定
 * （{@code FAIL_ON_UNKNOWN_PROPERTIES=false}）では未知propertyが黙って捨てられるため、{@link JsonAnySetter} で
 * 名前だけを集め（値は保持しない）、coordinatorが400にする（{@code AiRestController.AiChatRequest} と同じ型）。</p>
 */
public class DualConfirmationDocumentSelectionRequest {

    private Long documentId;
    private Long documentVersionId;
    private final Set<String> unknownFields = new LinkedHashSet<>();

    public Long getDocumentId() {
        return documentId;
    }

    public void setDocumentId(Long documentId) {
        this.documentId = documentId;
    }

    public Long getDocumentVersionId() {
        return documentVersionId;
    }

    public void setDocumentVersionId(Long documentVersionId) {
        this.documentVersionId = documentVersionId;
    }

    @JsonAnySetter
    public void putUnknown(String name, Object ignoredValue) {
        // 値（client指定のhash等）は保持しない。名前だけを拒否の判定に使う。
        unknownFields.add(name);
    }

    @JsonIgnore
    public Set<String> getUnknownFields() {
        return Collections.unmodifiableSet(unknownFields);
    }
}
