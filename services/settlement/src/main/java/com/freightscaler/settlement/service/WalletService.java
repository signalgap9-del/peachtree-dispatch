package com.freightscaler.settlement.service;

import com.freightscaler.settlement.model.Wallet;
import com.freightscaler.settlement.repository.WalletRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Wallet operations for settlement payments.
 * Debit uses an atomic conditional UPDATE (WHERE balance >= amount)
 * to prevent negative balances without pessimistic locking.
 */
@Service
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);

    private final WalletRepository walletRepository;

    public WalletService(WalletRepository walletRepository) {
        this.walletRepository = walletRepository;
    }

    /**
     * Atomically debit a wallet. Only succeeds if balance >= amountCents.
     *
     * @return true if the debit succeeded, false if insufficient funds or wallet missing
     */
    public boolean debit(UUID ownerId, long amountCents) {
        int rows = walletRepository.debit(ownerId, amountCents);
        if (rows == 0) {
            log.warn("Debit failed for wallet owner={}: insufficient funds or wallet not found (amount={} cents)",
                    ownerId, amountCents);
            return false;
        }
        log.info("Debited {} cents from wallet owner={}", amountCents, ownerId);
        return true;
    }

    /**
     * Credit a wallet. Always succeeds if the wallet exists.
     *
     * @return true if the credit succeeded, false if wallet not found
     */
    public boolean credit(UUID ownerId, long amountCents) {
        int rows = walletRepository.credit(ownerId, amountCents);
        if (rows == 0) {
            log.warn("Credit failed for wallet owner={}: wallet not found (amount={} cents)", ownerId, amountCents);
            return false;
        }
        log.info("Credited {} cents to wallet owner={}", amountCents, ownerId);
        return true;
    }

    public Optional<Wallet> getBalance(UUID ownerId) {
        return walletRepository.findById(ownerId);
    }

    /**
     * Creates a wallet with an initial balance. Idempotent (ON CONFLICT DO NOTHING).
     *
     * @return true if a new wallet was created, false if it already existed
     */
    public boolean createWallet(UUID ownerId, long initialBalanceCents) {
        int rows = walletRepository.insert(ownerId, initialBalanceCents);
        if (rows > 0) {
            log.info("Created wallet for owner={} with balance={} cents", ownerId, initialBalanceCents);
        }
        return rows > 0;
    }
}
