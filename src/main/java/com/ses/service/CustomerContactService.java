package com.ses.service;

import com.ses.dto.customer.CustomerContactDto;
import com.ses.dto.customer.CustomerContactSaveRequest;
import com.ses.entity.CustomerContact;

import java.time.LocalDate;
import java.util.List;

public interface CustomerContactService {
    List<CustomerContactDto> list(Long customerId, LocalDate asOf);
    List<CustomerContactDto> recipientCandidates(Long customerId, LocalDate asOf, String role);

    default List<CustomerContactDto> recipientCandidates(Long customerId, LocalDate asOf) {
        return recipientCandidates(customerId, asOf, null);
    }
    List<CustomerContactDto> duplicateCandidates(Long customerId, String email, String phone, Long excludeId);
    CustomerContactDto create(Long customerId, CustomerContactSaveRequest request);
    CustomerContactDto update(Long customerId, Long contactId, CustomerContactSaveRequest request);
    CustomerContactDto retire(Long customerId, Long contactId, LocalDate validTo, Integer version);
    String resolveRecipientEmail(Long customerId, Long contactId, LocalDate asOf);
    CustomerContact getOwnedOrThrow(Long customerId, Long contactId);
}
