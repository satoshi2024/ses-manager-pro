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

    /**
     * 評価が PASSED かつ管理者承認済みの candidate だけを ACTIVE にする。自動昇格はしない。
     */
    AiArtifactVersion promoteApproved(Long evaluationId);

    /**
     * 指定 version を ACTIVE に戻す。過去 run の artifact_version_id は書き換えない。
     */
    AiArtifactVersion rollbackTo(Long versionId);
}
