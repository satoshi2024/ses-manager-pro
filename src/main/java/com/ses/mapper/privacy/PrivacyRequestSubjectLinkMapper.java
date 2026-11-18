package com.ses.mapper.privacy;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.privacy.PrivacyRequestSubjectLink;
import org.apache.ibatis.annotations.Mapper;

/** DSAR と本人の明示 link（t_privacy_request_subject_link）。書くのは {@code PrivacyRequestService} だけ。 */
@Mapper
public interface PrivacyRequestSubjectLinkMapper extends BaseMapper<PrivacyRequestSubjectLink> {
}
