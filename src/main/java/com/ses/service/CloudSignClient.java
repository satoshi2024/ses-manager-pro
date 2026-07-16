package com.ses.service;
import com.ses.entity.ContractDocument;
public interface CloudSignClient { Result send(ContractDocument document); Result status(String documentId); record Result(String documentId,String fileId,String status,byte[] signedPdf,byte[] certificate) {} }
