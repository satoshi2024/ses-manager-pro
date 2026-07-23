package com.ses.service.impl;

import com.ses.entity.SkillTag;
import com.ses.mapper.SkillTagMapper;
import com.ses.service.SkillTagResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class SkillTagResolverTest {

    @Mock
    private SkillTagMapper skillTagMapper;

    @InjectMocks
    private SkillTagResolver skillTagResolver;

    @Test
    void resolveOrCrate_createsNewTag() {
        when(skillTagMapper.selectList(any())).thenReturn(Collections.emptyList());

        Long resolvedId = skillTagResolver.resolveOrCreate("Java");

        verify(skillTagMapper).insert(any(SkillTag.class));
    }

    @Test
    void resolveOrCrate_returnsExistingTag() {
        SkillTag tag = new SkillTag();
        tag.setId(10L);
        tag.setSkillName("Java");
        when(skillTagMapper.selectList(any())).thenReturn(Collections.singletonList(tag));

        Long resolvedId = skillTagResolver.resolveOrCreate("Java");

        assertEquals(10L, resolvedId);
        verify(skillTagMapper, never()).insert(any(SkillTag.class));
    }
}
