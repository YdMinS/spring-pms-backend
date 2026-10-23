package com.pms.service.dashboard;

import com.pms.domain.DashboardCard;
import com.pms.domain.DashboardCardType;
import com.pms.domain.DashboardPeriod;
import com.pms.domain.MemberDashboard;
import com.pms.domain.User;
import com.pms.dto.request.DashboardMobileLayoutRequest;
import com.pms.dto.request.DashboardWebLayoutRequest;
import com.pms.dto.response.DashboardLayoutResponse;
import com.pms.exception.UnauthorizedException;
import com.pms.repository.MemberDashboardRepository;
import com.pms.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** {@link DashboardLayoutService} implementation (FEATURE_2610_29). */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardLayoutServiceImpl implements DashboardLayoutService {

    private final UserRepository userRepository;
    private final MemberDashboardRepository memberDashboardRepository;

    @Override
    public DashboardLayoutResponse layout(String email, boolean admin) {
        User member = member(email);
        return memberDashboardRepository.findByMember_Id(member.getId())
                .map(row -> new DashboardLayoutResponse(admin, true, row.getCards()))
                .orElseGet(() -> new DashboardLayoutResponse(admin, false, List.of()));
    }

    @Override
    @Transactional
    public DashboardLayoutResponse saveWeb(String email, boolean admin, DashboardWebLayoutRequest request) {
        if (request == null || request.cards() == null) {
            throw new IllegalArgumentException("카드 목록이 없습니다.");
        }
        User member = member(email);
        MemberDashboard row = memberDashboardRepository.findByMember_Id(member.getId()).orElse(null);
        Map<DashboardCardType, DashboardCard> stored = byType(row);
        Set<DashboardCardType> seen = EnumSet.noneOf(DashboardCardType.class);
        List<DashboardCard> cards = new ArrayList<>();
        for (DashboardWebLayoutRequest.Card card : request.cards()) {
            check(card.cardType(), card.period(), admin, seen);
            if (card.x() == null || card.x() < 0 || card.y() == null || card.y() < 0
                    || card.w() == null || card.w() < 1 || card.h() == null || card.h() < 1) {
                throw new IllegalArgumentException("카드 위치·크기 값이 올바르지 않습니다: " + card.cardType());
            }
            DashboardCard before = stored.get(card.cardType());
            cards.add(new DashboardCard(card.cardType(), card.period(), card.x(), card.y(), card.w(), card.h(),
                    before == null ? null : before.mobileOrder(), before == null ? null : before.mobileW()));
        }
        return save(member, row, keepHiddenCards(cards, stored, admin), admin);
    }

    @Override
    @Transactional
    public DashboardLayoutResponse saveMobile(String email, boolean admin, DashboardMobileLayoutRequest request) {
        if (request == null || request.cards() == null) {
            throw new IllegalArgumentException("카드 목록이 없습니다.");
        }
        User member = member(email);
        MemberDashboard row = memberDashboardRepository.findByMember_Id(member.getId()).orElse(null);
        Map<DashboardCardType, DashboardCard> stored = byType(row);
        Set<DashboardCardType> seen = EnumSet.noneOf(DashboardCardType.class);
        List<DashboardCard> cards = new ArrayList<>();
        for (DashboardMobileLayoutRequest.Card card : request.cards()) {
            check(card.cardType(), card.period(), admin, seen);
            if (card.order() == null || card.order() < 0 || card.w() == null || card.w() < 1) {
                throw new IllegalArgumentException("카드 위치·크기 값이 올바르지 않습니다: " + card.cardType());
            }
            DashboardCard before = stored.get(card.cardType());
            cards.add(new DashboardCard(card.cardType(), card.period(),
                    before == null ? null : before.webX(), before == null ? null : before.webY(),
                    before == null ? null : before.webW(), before == null ? null : before.webH(),
                    card.order(), card.w()));
        }
        return save(member, row, keepHiddenCards(cards, stored, admin), admin);
    }

    /** Checks shared by both saves: a card type, once, allowed for the role (D9), with an allowed period (D22 · D23). */
    private static void check(DashboardCardType type, DashboardPeriod period, boolean admin,
                              Set<DashboardCardType> seen) {
        if (type == null) {
            throw new IllegalArgumentException("카드 종류가 없습니다.");
        }
        if (!seen.add(type)) {
            throw new IllegalArgumentException("같은 카드가 두 번 들어 있습니다: " + type);
        }
        if (type.isAdminOnly() && !admin) {
            throw new IllegalArgumentException("관리자 전용 카드입니다: " + type);
        }
        if (!type.allows(period)) {
            throw new IllegalArgumentException("이 카드에 쓸 수 없는 기간입니다: " + type + " " + period);
        }
    }

    /** A non-admin does not see the admin cards, so its save keeps them as stored (D9). */
    private static List<DashboardCard> keepHiddenCards(List<DashboardCard> cards,
                                                       Map<DashboardCardType, DashboardCard> stored,
                                                       boolean admin) {
        if (admin) {
            return cards;
        }
        List<DashboardCard> result = new ArrayList<>(cards);
        stored.values().stream()
                .filter(card -> card.cardType().isAdminOnly())
                .forEach(result::add);
        return result;
    }

    private DashboardLayoutResponse save(User member, MemberDashboard row, List<DashboardCard> cards,
                                         boolean admin) {
        MemberDashboard saved = memberDashboardRepository.save(row == null
                ? MemberDashboard.builder().member(member).cards(cards).build()
                : row.toBuilder().cards(cards).build());
        return new DashboardLayoutResponse(admin, true, saved.getCards());
    }

    private static Map<DashboardCardType, DashboardCard> byType(MemberDashboard row) {
        Map<DashboardCardType, DashboardCard> result = new EnumMap<>(DashboardCardType.class);
        if (row != null) {
            row.getCards().forEach(card -> result.put(card.cardType(), card));
        }
        return result;
    }

    private User member(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new UnauthorizedException("User not found"));
    }
}
