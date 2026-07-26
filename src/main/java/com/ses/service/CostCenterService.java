package com.ses.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ses.entity.CostCenter;

import java.io.Serializable;

/** 原価部門マスタサービス。 */
public interface CostCenterService extends IService<CostCenter> {

    boolean deactivate(Long costCenterId);

    @Override
    boolean removeById(Serializable id);
}
