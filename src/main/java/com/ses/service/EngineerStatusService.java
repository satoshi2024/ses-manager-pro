package com.ses.service;

public interface EngineerStatusService {
    void onProposalCreated(Long engineerId);
    void onContractActive(Long engineerId);
    void releaseIfIdle(Long engineerId);
}
