package com.ses.dto.lifecycle.dual;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 二者確認の提出body（NF01細粒度epoch計画 §5.2 A3・A7）。
 *
 * <p>受け付けるのは {@code taskGeneration}（必須）・{@code documentSelections}（1件）・{@code comment}（1000字以内）だけである。
 * actor・tenant・role・証跡hash・storage key・title・scan状態は受け付けず、未知propertyは {@link JsonAnySetter} で名前だけを
 * 集めてcoordinatorが400にする（値は保持しない）。{@code Idempotency-Key} はheaderで受け取る。</p>
 */
public class DualConfirmationSubmitRequest {

    private Long taskGeneration;
    private List<DualConfirmationDocumentSelectionRequest> documentSelections;
    private String comment;
    private final Set<String> unknownFields = new LinkedHashSet<>();

    public Long getTaskGeneration() {
        return taskGeneration;
    }

    public void setTaskGeneration(Long taskGeneration) {
        this.taskGeneration = taskGeneration;
    }

    public List<DualConfirmationDocumentSelectionRequest> getDocumentSelections() {
        return documentSelections;
    }

    public void setDocumentSelections(List<DualConfirmationDocumentSelectionRequest> documentSelections) {
        this.documentSelections = documentSelections;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    @JsonAnySetter
    public void putUnknown(String name, Object ignoredValue) {
        unknownFields.add(name);
    }

    /** body直下と各文書選択の未知property名（値は持たない）。 */
    @JsonIgnore
    public Set<String> getUnknownFields() {
        Set<String> names = new LinkedHashSet<>(unknownFields);
        if (documentSelections != null) {
            for (DualConfirmationDocumentSelectionRequest selection : documentSelections) {
                if (selection != null) {
                    selection.getUnknownFields().forEach(name -> names.add("documentSelections." + name));
                }
            }
        }
        return Collections.unmodifiableSet(names);
    }
}
