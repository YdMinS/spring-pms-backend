package com.pms.domain;

import com.pms.domain.converter.DashboardCardListConverter;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.util.List;

/**
 * The dashboard of one member (FEATURE_2610_29 D6 · D11) — one row per member, made by the first save. No row = the
 * member has not saved yet and the screen draws the default cards (D19).
 *
 * <p>No {@code @TenantId}: the row hangs off {@code member}, which is itself outside the tenant filter (see
 * {@link User}) — the same as {@link RefreshToken}. Deleting the member deletes the row (ON DELETE CASCADE,
 * changeset 117).</p>
 *
 * <p>⚠️ Immutable (no {@code @Setter}) — updates rebuild via {@code toBuilder}.</p>
 */
@Entity
@Table(name = "member_dashboard",
        uniqueConstraints = @UniqueConstraint(name = "uq_member_dashboard_member", columnNames = "member_id"))
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class MemberDashboard extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User member;

    @Convert(converter = DashboardCardListConverter.class)
    @Column(name = "cards", nullable = false, columnDefinition = "TEXT")
    private List<DashboardCard> cards;
}
