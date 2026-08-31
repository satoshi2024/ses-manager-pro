package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.CustomerHealthSnapshot;
import org.apache.ibatis.annotations.Mapper;

/**
 * 顧客ヘルススナップショットマッパー
 */
@Mapper
public interface CustomerHealthSnapshotMapper extends BaseMapper<CustomerHealthSnapshot> {
}
