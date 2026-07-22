package com.ses.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.entity.RoleMenu;
import com.ses.mapper.RoleMenuMapper;
import com.ses.service.RoleMenuService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * ロール別メニュー権限サービス実装クラス
 */
@Service
public class RoleMenuServiceImpl extends ServiceImpl<RoleMenuMapper, RoleMenu> implements RoleMenuService {

    @Override
    public List<String> getMenuKeysByRole(String role) {
        return baseMapper.selectMenuKeysByRole(role);
    }

    @Override
    public List<String> getAllMenuKeys() {
        return baseMapper.selectAllMenuKeys();
    }
}
