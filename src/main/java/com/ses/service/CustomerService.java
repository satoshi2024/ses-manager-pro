package com.ses.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ses.entity.Customer;

/**
 * 顧客サービスインターフェース
 */
public interface CustomerService extends IService<Customer> {

    /**
     * 楽観ロック付き更新。version 未指定・競合時は 409。
     * 存在しない ID は 404（競合を 404 に落とさない）。
     */
    boolean updateWithOptimisticLock(Customer customer);
}
