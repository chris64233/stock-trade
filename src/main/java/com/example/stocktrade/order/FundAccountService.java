package com.example.stocktrade.order;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 资金账户的并发安全初始化。
 * 账户行创建放在独立事务中：并发首笔结算时插入冲突只会回滚嵌套事务，
 * 不污染外层结算事务；调用方随后在外层事务中对账户行加悲观写锁串行化资金变动。
 * 调用方必须先持有委托行锁（固定 委托 -&gt; 账户 的加锁顺序，避免死锁）。
 */
@Service
public class FundAccountService {

    private final FundAccountRepository fundAccountRepository;

    public FundAccountService(FundAccountRepository fundAccountRepository) {
        this.fundAccountRepository = fundAccountRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ensureExists(String accountId) {
        if (fundAccountRepository.findByAccountId(accountId).isPresent()) {
            return;
        }
        try {
            fundAccountRepository.saveAndFlush(new FundAccount(accountId));
        } catch (DataIntegrityViolationException ignored) {
            // 并发下其他事务已创建账户；嵌套事务回滚即可，外层事务重新加锁读取
        }
    }
}
