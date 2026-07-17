package com.ses.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ses.entity.Engineer;

/**
 * エンジニアサービスインターフェース
 */
public interface EngineerService extends IService<Engineer> {

    /**
     * 稼動ステータスの整合性ガード付き更新。
     * ステータスが変化する場合のみ、稼動中契約の有無と矛盾しないことを検証する
     * （稼動中契約が無いのに「稼動中」/稼動中契約があるのに「Bench」を拒否）。
     * @return 更新成功なら true
     */
    boolean updateWithStatusGuard(Engineer engineer);
}
