package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.dto.analytics.ContractDateRangeDto;
import com.ses.entity.Contract;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 契約マッパー
 */
@Mapper
public interface ContractMapper extends BaseMapper<Contract> {

    /**
     * 稼動率推移の集計用に engineer_id/start_date/end_date のみを取得する軽量プロジェクション
     * （稼動中・終了の契約に限定してSQL側で絞り込む）。単価・備考等の大きな列を
     * メモリに載せずに済む。
     */
    @Select("SELECT engineer_id, start_date, end_date FROM t_contract " +
            "WHERE deleted_flag = 0 AND status IN ('稼動中','終了') AND engineer_id IS NOT NULL AND start_date IS NOT NULL")
    List<ContractDateRangeDto> selectActiveDateRanges();
}
