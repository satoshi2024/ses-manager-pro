package com.ses.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.entity.Contract;
import com.ses.mapper.ContractMapper;
import com.ses.service.ContractService;
import org.springframework.stereotype.Service;

/**
 * 契約サービス実装
 */
@Service
public class ContractServiceImpl extends ServiceImpl<ContractMapper, Contract> implements ContractService {
}
