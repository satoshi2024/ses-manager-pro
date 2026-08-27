package com.ses.config;

import com.ses.mapper.SysUserMapper;
import com.ses.mapper.UserMfaMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@ActiveProfiles("test")
@Sql(scripts = "/sql/production-security-users-h2.sql",
        config = @org.springframework.test.context.jdbc.SqlConfig(encoding = "UTF-8"))
@Transactional
class ProductionSecurityEnrollmentFixtureTest {

    @Autowired SysUserMapper sysUserMapper;
    @Autowired UserMfaMapper userMfaMapper;

    @Test
    void 二つのbreakGlassアカウントはMFA登録済みである() {
        assertEquals(1, userMfaMapper.countEnrolled("default", sysUserMapper.selectByUsername("admin").getId()));
        assertEquals(1, userMfaMapper.countEnrolled("default", sysUserMapper.selectByUsername("breakglass2").getId()));
    }
}
