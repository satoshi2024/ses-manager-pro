package com.ses.service;

import com.ses.dto.crm.LeadConversionDto;
import com.ses.dto.crm.LeadSaveRequest;
import com.ses.entity.Lead;

import java.util.List;

/** リードの可視性・重複候補・冪等転換を一元管理するサービス。 */
public interface LeadService {
    List<Lead> list(String status, String companyName);
    Lead getVisible(Long id);
    Lead create(LeadSaveRequest request);
    Lead update(Long id, LeadSaveRequest request);
    List<Lead> duplicateCandidates(String companyName, String contactEmail, String contactPhone, Long excludeId);
    LeadConversionDto convert(Long id, Integer expectedVersion);
}
