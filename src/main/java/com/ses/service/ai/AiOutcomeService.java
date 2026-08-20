package com.ses.service.ai;

import com.ses.entity.Contract;
import com.ses.entity.Opportunity;
import com.ses.entity.Proposal;

import java.time.LocalDate;

public interface AiOutcomeService {

    void onProposalSaved(Proposal proposal);

    void onProposalStatusChanged(Proposal proposal);

    void onOpportunityStageChanged(Opportunity opportunity);

    void onContractCancelled(Contract contract, LocalDate originalEndDate, LocalDate cancelDate);

    void onContractRenewalContinued(Contract contract);

    void onContractPositionLinked(Contract contract);
}
