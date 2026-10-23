package com.pms.service.dashboard;

import com.pms.domain.DashboardCard;
import com.pms.domain.DashboardCardType;
import com.pms.domain.DashboardPeriod;
import com.pms.domain.MemberDashboard;
import com.pms.domain.Role;
import com.pms.domain.User;
import com.pms.dto.request.DashboardMobileLayoutRequest;
import com.pms.dto.request.DashboardWebLayoutRequest;
import com.pms.dto.response.DashboardLayoutResponse;
import com.pms.repository.MemberDashboardRepository;
import com.pms.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Dashboard save rules (FEATURE_2610_29 D9 · D11): web and mobile keep each other's place, a missing card is
 * removed, a save by a non-admin keeps the stored admin cards, and the 400 checks.
 */
@ExtendWith(MockitoExtension.class)
class DashboardLayoutServiceImplTest {

    private static final String EMAIL = "staff@test.com";

    @Mock private UserRepository userRepository;
    @Mock private MemberDashboardRepository memberDashboardRepository;

    @InjectMocks private DashboardLayoutServiceImpl service;

    private final User member = User.builder().id(7L).email(EMAIL).name("직원").password("x").role(Role.USER).build();

    @BeforeEach
    void setUp() {
        given(userRepository.findByEmail(EMAIL)).willReturn(Optional.of(member));
    }

    @Test
    void layoutWithoutRowIsNotSaved() {
        given(memberDashboardRepository.findByMember_Id(7L)).willReturn(Optional.empty());

        DashboardLayoutResponse response = service.layout(EMAIL, true);

        assertThat(response.admin()).isTrue();
        assertThat(response.saved()).isFalse();
        assertThat(response.cards()).isEmpty();
    }

    /** D11: a web save keeps the stored mobile place, a new card has no mobile place, a missing card is removed. */
    @Test
    void saveWebKeepsMobilePlaceAndRemovesMissingCards() {
        givenStored(
                new DashboardCard(DashboardCardType.PAID_ORDERS, null, 0, 0, 1, 1, 0, 1),
                new DashboardCard(DashboardCardType.NEW_ORDERS, null, 1, 0, 1, 1, 1, 1));
        givenSaveReturnsArgument();

        service.saveWeb(EMAIL, true, new DashboardWebLayoutRequest(List.of(
                new DashboardWebLayoutRequest.Card(DashboardCardType.PAID_ORDERS, null, 2, 1, 2, 1),
                new DashboardWebLayoutRequest.Card(DashboardCardType.DAILY_SALES_CHART, DashboardPeriod.DAYS_30,
                        0, 2, 4, 2))));

        assertThat(savedCards()).containsExactly(
                new DashboardCard(DashboardCardType.PAID_ORDERS, null, 2, 1, 2, 1, 0, 1),
                new DashboardCard(DashboardCardType.DAILY_SALES_CHART, DashboardPeriod.DAYS_30, 0, 2, 4, 2,
                        null, null));
    }

    /** D11: a mobile save keeps the stored web place; the period follows the request. */
    @Test
    void saveMobileKeepsWebPlace() {
        givenStored(new DashboardCard(DashboardCardType.PERIOD_SALES, DashboardPeriod.THIS_MONTH, 0, 0, 2, 1,
                null, null));
        givenSaveReturnsArgument();

        service.saveMobile(EMAIL, true, new DashboardMobileLayoutRequest(List.of(
                new DashboardMobileLayoutRequest.Card(DashboardCardType.PERIOD_SALES, DashboardPeriod.TODAY, 0, 2))));

        assertThat(savedCards()).containsExactly(
                new DashboardCard(DashboardCardType.PERIOD_SALES, DashboardPeriod.TODAY, 0, 0, 2, 1, 0, 2));
    }

    /** D9: a non-admin does not see the admin cards, so its save keeps them as stored. */
    @Test
    void nonAdminSaveKeepsStoredAdminOnlyCards() {
        DashboardCard chart = new DashboardCard(DashboardCardType.CHANNEL_SALES_CHART, null, 4, 0, 4, 2, 4, 2);
        givenStored(new DashboardCard(DashboardCardType.PAID_ORDERS, null, 0, 0, 1, 1, 0, 1), chart);
        givenSaveReturnsArgument();

        DashboardLayoutResponse response = service.saveWeb(EMAIL, false, new DashboardWebLayoutRequest(List.of(
                new DashboardWebLayoutRequest.Card(DashboardCardType.OPEN_CLAIMS, null, 0, 0, 1, 1))));

        assertThat(response.admin()).isFalse();
        assertThat(savedCards()).containsExactly(
                new DashboardCard(DashboardCardType.OPEN_CLAIMS, null, 0, 0, 1, 1, null, null), chart);
    }

    @Test
    void nonAdminCannotSaveAdminOnlyCard() {
        givenStored();

        assertThatThrownBy(() -> service.saveWeb(EMAIL, false, new DashboardWebLayoutRequest(List.of(
                new DashboardWebLayoutRequest.Card(DashboardCardType.PERIOD_SALES, null, 0, 0, 2, 1)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("관리자 전용 카드입니다: PERIOD_SALES");
        verify(memberDashboardRepository, never()).save(any());
    }

    @Test
    void duplicateCardIsRejected() {
        givenStored();

        assertThatThrownBy(() -> service.saveMobile(EMAIL, true, new DashboardMobileLayoutRequest(List.of(
                new DashboardMobileLayoutRequest.Card(DashboardCardType.NEW_ORDERS, null, 0, 1),
                new DashboardMobileLayoutRequest.Card(DashboardCardType.NEW_ORDERS, null, 1, 1)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("같은 카드가 두 번 들어 있습니다: NEW_ORDERS");
    }

    @Test
    void periodNotAllowedForCardIsRejected() {
        givenStored();

        assertThatThrownBy(() -> service.saveWeb(EMAIL, true, new DashboardWebLayoutRequest(List.of(
                new DashboardWebLayoutRequest.Card(DashboardCardType.DAILY_SALES_CHART, DashboardPeriod.TODAY,
                        0, 0, 4, 2)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("이 카드에 쓸 수 없는 기간입니다: DAILY_SALES_CHART TODAY");
    }

    @Test
    void negativePlaceIsRejected() {
        givenStored();

        assertThatThrownBy(() -> service.saveWeb(EMAIL, true, new DashboardWebLayoutRequest(List.of(
                new DashboardWebLayoutRequest.Card(DashboardCardType.PAID_ORDERS, null, -1, 0, 1, 1)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("카드 위치·크기 값이 올바르지 않습니다: PAID_ORDERS");
    }

    private void givenStored(DashboardCard... cards) {
        given(memberDashboardRepository.findByMember_Id(7L)).willReturn(cards.length == 0
                ? Optional.empty()
                : Optional.of(MemberDashboard.builder().id(3L).member(member).cards(List.of(cards)).build()));
    }

    private void givenSaveReturnsArgument() {
        given(memberDashboardRepository.save(any(MemberDashboard.class)))
                .willAnswer(invocation -> invocation.getArgument(0));
    }

    private List<DashboardCard> savedCards() {
        ArgumentCaptor<MemberDashboard> captor = ArgumentCaptor.forClass(MemberDashboard.class);
        verify(memberDashboardRepository).save(captor.capture());
        return captor.getValue().getCards();
    }
}
