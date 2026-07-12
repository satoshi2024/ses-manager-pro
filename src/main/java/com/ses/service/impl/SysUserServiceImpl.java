package com.ses.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.entity.SysUser;
import com.ses.mapper.SysUserMapper;
import com.ses.service.SysUserService;
import org.springframework.stereotype.Service;

/**
 * システムユーザーサービス実装クラス
 */
@Service
public class SysUserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements SysUserService {
}
