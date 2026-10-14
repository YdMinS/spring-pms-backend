package com.pms.service.elevenst;

import com.pms.repository.ElevenstAccountCredentialRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * D88: records that an account's 11st key was just used successfully. REQUIRES_NEW so a caller that rolls
 * back afterwards (e.g. a later step of a register) still keeps the stamp — the key WAS used.
 */
@Component
@RequiredArgsConstructor
public class ElevenstKeyUsageRecorder {

    private final ElevenstAccountCredentialRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void touch(Long accountId) {
        if (accountId == null) {
            return;
        }
        repository.touchKeyUsedAt(accountId, LocalDateTime.now());
    }
}
