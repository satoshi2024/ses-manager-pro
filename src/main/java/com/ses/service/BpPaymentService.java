package com.ses.service;

import com.ses.dto.bp.BpPaymentTreeDto;
import com.ses.entity.BpPayment;

import java.util.List;

public interface BpPaymentService {
    List<BpPaymentTreeDto> getTreeByWorkRecordId(Long workRecordId);
    BpPayment addLayer(BpPayment bpPayment);
    BpPayment updateLayer(Long id, BpPayment bpPayment);
    void deleteLayer(Long id);
}
