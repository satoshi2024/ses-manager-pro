package com.ses.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.entity.Customer;
import com.ses.mapper.CustomerMapper;
import com.ses.service.CustomerService;
import org.springframework.stereotype.Service;

/**
 * 顧客サービス実装クラス
 */
@Service
public class CustomerServiceImpl extends ServiceImpl<CustomerMapper, Customer> implements CustomerService {
}
