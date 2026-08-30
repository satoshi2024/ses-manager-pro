package com.ses.service.pwa;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Supplier;

/** 要員PWAのmutationをledgerと業務更新を含む一つのtransactionで実行する境界。 */
@Service
public class PwaMutationTransactionService {

    @Transactional(rollbackFor = Exception.class)
    public <T> T execute(Supplier<T> command) {
        return command.get();
    }
}
