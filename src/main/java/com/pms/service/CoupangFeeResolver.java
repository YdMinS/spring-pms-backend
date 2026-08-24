package com.pms.service;

import com.pms.domain.CoupangFeeReference;
import com.pms.repository.CoupangFeeReferenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Hierarchical (소 &gt; 중 &gt; 대-default) fallback lookup over the seeded {@link CoupangFeeReference}
 * table (FEATURE_2608_06 / 46). Pure logic (no I/O beyond the repo) — unit-tested with a mocked repo.
 *
 * <p><b>Deterministic token decomposition</b> of a Coupang category display path:</p>
 * <ul>
 *   <li>Split on {@code '>'} ONLY. A leaf name's internal {@code '/'} (e.g. {@code "청소/세탁/욕실용품"})
 *       is NOT a separator and is preserved.</li>
 *   <li>Last token = 소, first token = 대, and (only when there are exactly 3 tokens) the middle = 중.</li>
 *   <li>Each token and each DB value is <b>normalized</b> (strip whitespace, lower-case, keep {@code '/'})
 *       then compared with {@code equals} — exact match only, never {@code contains}.</li>
 * </ul>
 *
 * <p><b>Candidate narrowing:</b> with ≥2 tokens the first token narrows to {@code findByDae(first)} (if it
 * matches no row's {@code dae}, the result is empty); a single token (leaf name alone) scans
 * {@code findAll()}. Then, in order: sub match (last token == {@code so}) → middle match (middle token ==
 * {@code jung} with blank {@code so}) → major default (first token == {@code dae} with blank {@code jung}
 * and {@code so}). No match → {@link Optional#empty()} (prefill is skipped).</p>
 */
@Service
@RequiredArgsConstructor
public class CoupangFeeResolver {

    private final CoupangFeeReferenceRepository repository;

    /**
     * @param namePath Coupang category display path (e.g. {@code "생활용품 > 청소/세탁/욕실용품"}) or a
     *                 leaf name alone.
     * @return the base commission rate, or empty when nothing matches.
     */
    public Optional<BigDecimal> resolve(String namePath) {
        if (namePath == null || namePath.isBlank()) {
            return Optional.empty();
        }

        // Split on '>' only; drop empty tokens (keeps leaf-internal '/' intact).
        String[] raw = namePath.split(">");
        List<String> tokens = new java.util.ArrayList<>();
        for (String t : raw) {
            String trimmed = t.trim();
            if (!trimmed.isEmpty()) {
                tokens.add(trimmed);
            }
        }
        if (tokens.isEmpty()) {
            return Optional.empty();
        }

        String daeToken = tokens.get(0);
        String soToken = tokens.get(tokens.size() - 1);
        String jungToken = tokens.size() == 3 ? tokens.get(1) : null;

        List<CoupangFeeReference> candidates = tokens.size() >= 2
                ? repository.findByDae(daeToken)
                : repository.findAll();
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        // 1. Sub match: last token == so (so non-blank).
        String soNorm = normalize(soToken);
        for (CoupangFeeReference c : candidates) {
            if (isPresent(c.getSo()) && normalize(c.getSo()).equals(soNorm)) {
                return Optional.of(c.getRate());
            }
        }

        // 2. Middle match: middle token == jung with blank so (only when a middle token exists).
        if (jungToken != null) {
            String jungNorm = normalize(jungToken);
            for (CoupangFeeReference c : candidates) {
                if (isPresent(c.getJung()) && normalize(c.getJung()).equals(jungNorm) && !isPresent(c.getSo())) {
                    return Optional.of(c.getRate());
                }
            }
        }

        // 3. Major default: first token == dae with blank jung and so.
        String daeNorm = normalize(daeToken);
        for (CoupangFeeReference c : candidates) {
            if (normalize(c.getDae()).equals(daeNorm) && !isPresent(c.getJung()) && !isPresent(c.getSo())) {
                return Optional.of(c.getRate());
            }
        }

        return Optional.empty();
    }

    /** Strip all whitespace and lower-case; leaf-internal '/' is preserved. Null-safe → "". */
    private String normalize(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "").toLowerCase();
    }

    private boolean isPresent(String s) {
        return s != null && !s.isBlank();
    }
}
