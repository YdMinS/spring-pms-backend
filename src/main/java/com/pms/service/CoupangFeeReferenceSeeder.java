package com.pms.service;

import com.pms.domain.CoupangFeeReference;
import com.pms.repository.CoupangFeeReferenceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Seeds the static Coupang commission fee reference table on startup (FEATURE_2608_06 / 46).
 *
 * <p>Idempotent — if any {@link CoupangFeeReference} row already exists it does nothing (two runs still
 * leave exactly one copy). Reads {@code data/coupang-fee-table.csv} (columns {@code dae,jung,so,rate},
 * one flat row per category; blank {@code jung}/{@code so} = major/middle-default rows) and
 * {@code saveAll}s. The hierarchical inheritance is already resolved in the CSV, so this seeder is a
 * plain reader — no inheritance logic here.</p>
 *
 * <p>Runs {@code @Order(60)} — after the tenant-scoped startup seeders ({@link SystemFontSeeder} 50,
 * {@link DefaultTemplateSeeder} 51, {@link DefaultDetailTemplateSeeder} 52). This is global reference
 * data with NO {@code @TenantId}, so it needs no {@code TenantContext} and no {@code @Profile} (dev/prod
 * and the test H2 all need it).</p>
 */
@Slf4j
@Component
@Order(60)
@RequiredArgsConstructor
public class CoupangFeeReferenceSeeder implements ApplicationRunner {

    private static final String CSV = "data/coupang-fee-table.csv";

    private final CoupangFeeReferenceRepository repository;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (repository.count() > 0) {
            return;
        }
        List<CoupangFeeReference> rows = parseCsv();
        repository.saveAll(rows);
        log.info("Seeded {} Coupang fee reference rows", rows.size());
    }

    private List<CoupangFeeReference> parseCsv() throws Exception {
        List<CoupangFeeReference> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new ClassPathResource(CSV).getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            boolean header = true;
            while ((line = reader.readLine()) != null) {
                if (header) {                 // skip the "dae,jung,so,rate" header line
                    header = false;
                    continue;
                }
                if (line.isBlank()) {
                    continue;
                }
                // The CSV is pre-flattened and contains no embedded commas (category names use '/'),
                // so a plain split on ',' with 4 fields is safe. limit=-1 keeps trailing blanks.
                String[] f = line.split(",", -1);
                rows.add(CoupangFeeReference.builder()
                        .dae(f[0].trim())
                        .jung(f[1].trim())
                        .so(f[2].trim())
                        .rate(new BigDecimal(f[3].trim()))
                        .build());
            }
        }
        return rows;
    }
}
