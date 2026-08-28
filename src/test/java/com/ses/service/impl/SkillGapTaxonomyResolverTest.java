package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.entity.SkillTag;
import com.ses.entity.SkillTagAlias;
import com.ses.mapper.SkillTagAliasMapper;
import com.ses.mapper.SkillTagMapper;
import com.ses.service.SkillGapTaxonomyResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkillGapTaxonomyResolverTest {

    @Mock
    private SkillTagMapper skillTagMapper;
    @Mock
    private SkillTagAliasMapper aliasMapper;

    @Test
    void approvedAliasResolvesToCanonicalAndUnknownDoesNotInsert() {
        SkillTag java = new SkillTag();
        java.setId(10L);
        java.setSkillName("Java");
        SkillTagAlias alias = new SkillTagAlias();
        alias.setId(20L);
        alias.setAliasName("ジャバ");
        alias.setNormalizedAlias("ジャバ");
        alias.setCanonicalSkillId(10L);
        alias.setApprovedBy(99L);
        alias.setValidFrom(LocalDate.of(2026, 1, 1));
        when(skillTagMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(java));
        when(aliasMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(alias));
        when(skillTagMapper.selectById(10L)).thenReturn(java);
        SkillGapTaxonomyResolver resolver = new SkillGapTaxonomyResolver(skillTagMapper, aliasMapper);

        SkillGapTaxonomyResolver.Resolution resolved = resolver.resolveName(" ジャバ ", LocalDate.of(2026, 9, 1));
        SkillGapTaxonomyResolver.Resolution unknown = resolver.resolveName("未知クラウド", LocalDate.of(2026, 9, 1));

        assertEquals("ALIAS", resolved.resolution());
        assertEquals(10L, resolved.canonicalSkillId());
        assertTrue(unknown.unknown());
        verify(skillTagMapper, never()).insert(any(SkillTag.class));
    }
}
