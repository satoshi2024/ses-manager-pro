package com.ses.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ses.dto.crm.OpportunityConversionDto;
import com.ses.entity.Opportunity;

import java.util.List;

/** 商機サービス。状態機械と受注変換を一元管理する。 */
public interface OpportunityService extends IService<Opportunity> {

    /** 商機ステージを状態機械に従って変更する。 */
    Opportunity changeStage(Long id, String newStage, String lostReason, Integer expectedVersion);

    /** 受注済み商機を案件・見積へ冪等変換する。 */
    OpportunityConversionDto convertToProjectAndQuotation(Long id);

    /** 既存提案forecastへ移った商機を除いた、商機forecastの母集団を取得する。 */
    List<Opportunity> listForecastCandidates();
}
