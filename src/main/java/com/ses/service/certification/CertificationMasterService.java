package com.ses.service.certification;

import com.ses.entity.Certification;

public interface CertificationMasterService {

    Certification createMaster(Certification certification, Long actorUserId);
}
