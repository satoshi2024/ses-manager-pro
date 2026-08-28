package com.ses.service.certification;

import com.ses.entity.Certification;

import java.util.List;

public interface CertificationMasterService {

    Certification createMaster(Certification certification, Long actorUserId);

    List<Certification> listMasters(boolean includeInactive);

    Certification getMaster(Long id);

    Certification updateMaster(Long id, Certification certification, Long actorUserId);

    Certification deactivateMaster(Long id, Long actorUserId);
}
