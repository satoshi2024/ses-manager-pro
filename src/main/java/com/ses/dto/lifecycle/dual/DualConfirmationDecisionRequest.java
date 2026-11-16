package com.ses.dto.lifecycle.dual;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 二者確認の確認・差戻しbody（NF01細粒度epoch計画 §5.2 A4）。
 *
 * <p>{@code submissionRevision}・{@code evidenceSha256} は確認者が画面（A1）で見た提出を指し、
 * {@code ConfirmationBinding} として保存済みの提出と照合する（一致しなければ409 {@code error.lifecycle.submissionStale}）。
 * {@code decision} は {@code CONFIRM} または {@code REJECT}。未知propertyは名前だけを集めてcoordinatorが400にする。</p>
 */
public class DualConfirmationDecisionRequest {

    private Long submissionRevision;
    private String evidenceSha256;
    private String decision;
    private String comment;
    private final Set<String> unknownFields = new LinkedHashSet<>();

    public Long getSubmissionRevision() {
        return submissionRevision;
    }

    public void setSubmissionRevision(Long submissionRevision) {
        this.submissionRevision = submissionRevision;
    }

    public String getEvidenceSha256() {
        return evidenceSha256;
    }

    public void setEvidenceSha256(String evidenceSha256) {
        this.evidenceSha256 = evidenceSha256;
    }

    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
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

    @JsonIgnore
    public Set<String> getUnknownFields() {
        return Collections.unmodifiableSet(unknownFields);
    }
}
