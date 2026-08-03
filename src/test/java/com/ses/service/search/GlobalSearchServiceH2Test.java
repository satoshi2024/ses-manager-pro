package com.ses.service.search;

import com.ses.common.exception.BusinessException;
import com.ses.dto.search.GlobalSearchResultDTO;
import com.ses.entity.Customer;
import com.ses.entity.Engineer;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.service.security.DataScopeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class GlobalSearchServiceH2Test {

    @Autowired
    private GlobalSearchService globalSearchService;

    @Autowired
    private EngineerMapper engineerMapper;

    @Autowired
    private CustomerMapper customerMapper;

    @MockBean
    private DataScopeService dataScopeService;

    @Test
    void testSearchShortQueryRejected() {
        // 2文字未満は 400 BusinessException で拒否
        assertThrows(BusinessException.class, () -> globalSearchService.search("A", null));
        assertThrows(BusinessException.class, () -> globalSearchService.search(" ", null));
    }

    @Test
    void testSearchWithScopeIsolation() {
        // テストデータ作成
        Engineer eng1 = new Engineer();
        eng1.setFullName("山田 太郎");
        eng1.setEmploymentType("正社員");
        eng1.setStatus("稼動中");
        eng1.setResumeSummary("Java, Spring");
        engineerMapper.insert(eng1);

        Engineer eng2 = new Engineer();
        eng2.setFullName("山田 花子");
        eng2.setEmploymentType("正社員");
        eng2.setStatus("稼動中");
        eng2.setResumeSummary("Python, Django");
        engineerMapper.insert(eng2);

        Customer cust1 = new Customer();
        cust1.setCompanyName("山田商事");
        customerMapper.insert(cust1);

        // 営業A (DataScope 発動中): eng1 と cust1 のみ許可
        given(dataScopeService.isScoped()).willReturn(true);
        given(dataScopeService.allowedEngineerIds()).willReturn(Set.of(eng1.getId()));
        given(dataScopeService.allowedCustomerIds()).willReturn(Set.of(cust1.getId()));

        Map<String, List<GlobalSearchResultDTO>> results = globalSearchService.search("山田", null);

        // 要員の検索結果: eng1 のみヒット、eng2 は権限外のため件数にも含まれない (1件)
        List<GlobalSearchResultDTO> engineerResults = results.get("ENGINEER");
        assertNotNull(engineerResults);
        assertEquals(1, engineerResults.size());
        assertEquals("山田 太郎", engineerResults.get(0).getTitle());

        // 顧客の検索結果: cust1 がヒット
        List<GlobalSearchResultDTO> customerResults = results.get("CUSTOMER");
        assertNotNull(customerResults);
        assertEquals(1, customerResults.size());
        assertEquals("山田商事", customerResults.get(0).getTitle());
    }

    @Test
    void testSearchZeroAllowedReturnsZeroCount() {
        Engineer eng1 = new Engineer();
        eng1.setFullName("鈴木 一郎");
        eng1.setEmploymentType("正社員");
        eng1.setStatus("稼動中");
        engineerMapper.insert(eng1);

        // 営業B (DataScope 発動中): 許可集合が空 (0件)
        given(dataScopeService.isScoped()).willReturn(true);
        given(dataScopeService.allowedEngineerIds()).willReturn(Set.of());

        Map<String, List<GlobalSearchResultDTO>> results = globalSearchService.search("鈴木", List.of("ENGINEER"));

        List<GlobalSearchResultDTO> engineerResults = results.get("ENGINEER");
        assertNotNull(engineerResults);
        // 件数も含めて 0 件
        assertEquals(0, engineerResults.size());
    }
}
