package com.ses.service.ai;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ses.entity.AiArtifactVersion;

/**
 * AI artifact version の状態CAS。use case あたり ACTIVE は1つ。
 */
public interface AiArtifactVersionService extends IService<AiArtifactVersion> {

    /**
     * SHADOW を ACTIVE へ昇格する。既存 ACTIVE があれば CAS で RETIRED してから行う。
     * 同時昇格では片方だけが成功する。
     */
    AiArtifactVersion promoteToActive(Long candidateId);
}
