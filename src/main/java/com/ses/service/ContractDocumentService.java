package com.ses.service;
import com.baomidou.mybatisplus.extension.service.IService; import com.ses.entity.ContractDocument;
public interface ContractDocumentService extends IService<ContractDocument> { ContractDocument create(Long contractId, Long templateId, String recipientName, String recipientEmail); void send(Long id); void sync(Long id); }
