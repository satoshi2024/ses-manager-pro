package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.PortalTermsConsent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * ポータル利用規約同意マッパー（t_portal_terms_consent）。append-only。
 */
@Mapper
public interface PortalTermsConsentMapper extends BaseMapper<PortalTermsConsent> {

    /**
     * userの最新同意versionを返す（未同意ならnull）。
     * terms_versionは文字列のため辞書順でなく、append-onlyの行ID順で最新を取る。
     */
    @Select("SELECT terms_version FROM t_portal_terms_consent WHERE user_id = #{userId} ORDER BY id DESC LIMIT 1")
    String latestConsentedVersion(Long userId);
}
