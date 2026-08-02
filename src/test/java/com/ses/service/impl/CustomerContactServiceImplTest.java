package com.ses.service.impl;

import com.ses.entity.CustomerContact;
import com.ses.mapper.CustomerContactMapper;
import com.ses.mapper.CustomerMapper;
import com.ses.service.security.AuthorizationService;
import com.ses.service.security.DataScopeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CustomerContactServiceImplTest {
    @Mock private CustomerContactMapper mapper;
    @Mock private CustomerMapper customerMapper;
    @Mock private DataScopeService dataScopeService;
    @Mock private AuthorizationService authorizationService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rolesJsonは5値配列へ正規化して保存する() {
        CustomerContactServiceImpl service = service();
        CustomerContact saved = contact("[\"決裁者\",\"請求\"]");
        when(mapper.selectList(any())).thenReturn(List.of());
        when(mapper.insert(any(CustomerContact.class))).thenAnswer(invocation -> {
            CustomerContact value = invocation.getArgument(0);
            value.setId(1L);
            return 1;
        });
        when(mapper.selectById(1L)).thenReturn(saved);

        com.ses.dto.customer.CustomerContactSaveRequest request = request("[\"請求\",\"決裁者\"]");
        service.create(10L, request);

        ArgumentCaptor<CustomerContact> captor = ArgumentCaptor.forClass(CustomerContact.class);
        verify(mapper).insert(captor.capture());
        assertEquals("[\"請求\",\"決裁者\"]", captor.getValue().getRolesJson());
    }

    @Test
    void 不正なrolesJsonは400で拒否する() {
        CustomerContactServiceImpl service = service();
        when(mapper.selectList(any())).thenReturn(List.of());
        com.ses.dto.customer.CustomerContactSaveRequest request = request("決裁者,調達");

        var exception = assertThrows(com.ses.common.exception.BusinessException.class,
                () -> service.create(10L, request));
        assertEquals(400, exception.getCode());
        verify(mapper, never()).insert(any(CustomerContact.class));
    }

    @Test
    void PIIはaction許可時だけ平文で画面DTOへ返す() {
        CustomerContactServiceImpl service = service();
        when(mapper.selectList(any())).thenReturn(List.of(contact("[]")));
        when(authorizationService.isAllowed(any(), eq("customer.pii.view"))).thenReturn(false);

        var masked = service.list(10L, LocalDate.of(2026, 1, 1)).get(0);
        assertEquals("t***@example.com", masked.getEmail());
        assertEquals("***5678", masked.getPhone());

        when(authorizationService.isAllowed(any(), eq("customer.pii.view"))).thenReturn(true);
        var plain = service.list(10L, LocalDate.of(2026, 1, 1)).get(0);
        assertEquals("taro@example.com", plain.getEmail());
        assertEquals("090-1234-5678", plain.getPhone());
    }

    private CustomerContactServiceImpl service() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("sales", "n/a"));
        lenient().when(customerMapper.selectByIdForUpdate(10L)).thenReturn(new com.ses.entity.Customer());
        return new CustomerContactServiceImpl(mapper, customerMapper, dataScopeService, authorizationService, Clock.systemUTC());
    }

    private com.ses.dto.customer.CustomerContactSaveRequest request(String rolesJson) {
        var request = new com.ses.dto.customer.CustomerContactSaveRequest();
        request.setName("担当者");
        request.setRolesJson(rolesJson);
        request.setValidFrom(LocalDate.of(2026, 1, 1));
        request.setStatus("有効");
        request.setPrimaryFlag(0);
        return request;
    }

    private CustomerContact contact(String rolesJson) {
        CustomerContact value = new CustomerContact();
        value.setId(1L);
        value.setCustomerId(10L);
        value.setName("担当者");
        value.setRolesJson(rolesJson);
        value.setEmail("taro@example.com");
        value.setPhone("090-1234-5678");
        value.setValidFrom(LocalDate.of(2026, 1, 1));
        value.setStatus("有効");
        value.setPrimaryFlag(0);
        value.setVersion(1);
        return value;
    }
}
