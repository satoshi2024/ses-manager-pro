package com.ses.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 契約一覧と管理会計契約SQLの投影・scope条件を固定する。 */
class ContractMapperProjectionTest {

    @Test
    void 契約一覧SQLは顧客名と案件名を既定投影する() throws NoSuchMethodException {
        Select select = ContractMapper.class
                .getMethod("selectPageWithNames", com.baomidou.mybatisplus.extension.plugins.pagination.Page.class,
                        String.class, Long.class, Long.class, Long.class, String.class,
                        java.time.LocalDate.class, java.time.LocalDate.class, Long.class,
                        Boolean.class, java.time.LocalDate.class, java.time.LocalDate.class, java.util.List.class)
                .getAnnotation(Select.class);
        String sql = String.join("\n", select.value());
        assertTrue(sql.contains("cu.company_name AS customerName"));
        assertTrue(sql.contains("p.project_name AS projectName"));
    }

    @Test
    void 管理会計契約SQLは組織scopeと直属ユーザーscopeを持つ() throws NoSuchMethodException {
        Select select = ContractMapper.class.getMethod("selectAccountingContracts", java.time.LocalDate.class,
                java.time.LocalDate.class, boolean.class, java.util.List.class, java.util.List.class)
                .getAnnotation(Select.class);
        String sql = String.join("\n", select.value());
        assertTrue(sql.contains("directUserIds"));
        assertTrue(sql.contains("COALESCE(e.organization_id, uo.organization_id)"));
        assertTrue(sql.contains("AND ("));
    }

    @Test
    void 管理会計絞込SQLは契約法人直属ユーザー条件を持つ() throws NoSuchMethodException {
        Select select = ContractMapper.class.getMethod("selectAccountingContractsFiltered", java.time.LocalDate.class,
                java.time.LocalDate.class, boolean.class, java.util.List.class, java.util.List.class,
                java.util.List.class, Long.class, Long.class, Long.class, Long.class, Long.class, Long.class)
                .getAnnotation(Select.class);
        String sql = String.join("\n", select.value());
        assertTrue(sql.contains("allowedContractIds"));
        assertTrue(sql.contains("legalEntityId"));
        assertTrue(sql.contains("directUserIds"));
    }
}
