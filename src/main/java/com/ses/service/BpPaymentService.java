package com.ses.service;

import com.ses.dto.bp.BpPaymentTreeDto;
import com.ses.entity.BpPayment;

import java.util.List;

public interface BpPaymentService {
    void assertAllowed(Long bpPaymentId);
    List<BpPaymentTreeDto> getTreeByWorkRecordId(Long workRecordId);
    BpPayment addLayer(BpPayment bpPayment);
    BpPayment updateLayer(Long id, BpPayment bpPayment);
    void deleteLayer(Long id);
}
