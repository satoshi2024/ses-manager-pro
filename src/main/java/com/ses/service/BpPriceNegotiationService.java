package com.ses.service;

import com.ses.entity.BpPriceNegotiation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface BpPriceNegotiationService {

    BpPriceNegotiation requestNegotiation(Long bpCompanyId, BigDecimal requestedAmount, String summary, Long documentId);

    BpPriceNegotiation respondNegotiation(Long negotiationId, String status, BigDecimal agreedAmount, String summary);

    List<BpPriceNegotiation> getNegotiationsByBpCompany(Long bpCompanyId);
}
