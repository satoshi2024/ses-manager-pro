package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.ServiceStateEvent;
import org.apache.ibatis.annotations.Mapper;

/**
 * サービスリクエスト状態変更監査イベントマッパー
 */
@Mapper
public interface ServiceStateEventMapper extends BaseMapper<ServiceStateEvent> {
}
