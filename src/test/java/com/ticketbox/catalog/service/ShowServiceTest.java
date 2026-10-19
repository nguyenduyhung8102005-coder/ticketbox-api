package com.ticketbox.catalog.service;

import com.ticketbox.catalog.domain.Show;
import com.ticketbox.catalog.domain.ShowStatus;
import com.ticketbox.catalog.domain.ShowType;
import com.ticketbox.catalog.domain.ShowtimeStatus;
import com.ticketbox.catalog.dto.ShowDtos.ShowDetailResponse;
import com.ticketbox.catalog.dto.ShowDtos.ShowRequest;
import com.ticketbox.catalog.repository.ShowRepository;
import com.ticketbox.catalog.repository.ShowtimeRepository;
import com.ticketbox.common.exception.BusinessException;
import com.ticketbox.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static com.ticketbox.catalog.CatalogTestData.show;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShowServiceTest {

    @Mock
    private ShowRepository showRepository;
    @Mock
    private ShowtimeRepository showtimeRepository;

    private ShowService showService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-19T03:00:00Z"), ZoneOffset.UTC);
        showService = new ShowService(showRepository, showtimeRepository, clock, "Asia/Ho_Chi_Minh");
    }

    @Test
    void searchPublic_draftFilter_isRejected() {
        assertThatThrownBy(() -> showService.searchPublic(null, ShowStatus.DRAFT, null, Pageable.unpaged()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_ERROR);
        verifyNoInteractions(showRepository);
    }

    @Test
    void getPublicBySlug_draftShow_isHidden() {
        when(showRepository.findBySlug("phim-test")).thenReturn(Optional.of(show(1L, ShowStatus.DRAFT)));

        assertThatThrownBy(() -> showService.getPublicBySlug("phim-test"))
                .extracting("errorCode").isEqualTo(ErrorCode.SHOW_NOT_FOUND);
    }

    @Test
    void getPublicBySlug_publishedShow_isReturned() {
        when(showRepository.findBySlug("phim-test")).thenReturn(Optional.of(show(1L, ShowStatus.PUBLISHED)));

        assertThat(showService.getPublicBySlug("phim-test").title()).isEqualTo("Phim Test");
    }

    @Test
    void create_withoutSlug_generatesUniqueSlugFromTitle() {
        when(showRepository.existsBySlug("lat-mat-8")).thenReturn(true);
        when(showRepository.existsBySlug("lat-mat-8-2")).thenReturn(false);
        when(showRepository.save(any(Show.class))).thenAnswer(inv -> inv.getArgument(0));

        ShowDetailResponse created = showService.create(request("Lật Mặt 8", null));

        assertThat(created.slug()).isEqualTo("lat-mat-8-2");
        assertThat(created.status()).isEqualTo(ShowStatus.DRAFT);
    }

    @Test
    void create_withTakenSlug_isRejected() {
        when(showRepository.existsBySlug("mai")).thenReturn(true);

        assertThatThrownBy(() -> showService.create(request("Mai", "mai")))
                .extracting("errorCode").isEqualTo(ErrorCode.SLUG_ALREADY_EXISTS);
        verify(showRepository, never()).save(any());
    }

    @Test
    void update_keepingSameSlug_isAllowed() {
        Show existing = show(1L, ShowStatus.DRAFT);
        when(showRepository.findById(1L)).thenReturn(Optional.of(existing));

        ShowDetailResponse updated = showService.update(1L, request("Phim Test (bản mới)", "phim-test"));

        assertThat(updated.title()).isEqualTo("Phim Test (bản mới)");
        assertThat(updated.slug()).isEqualTo("phim-test");
        verify(showRepository, never()).existsBySlug(any());
    }

    @Test
    void delete_showWithShowtimes_isRejected() {
        when(showRepository.findById(1L)).thenReturn(Optional.of(show(1L, ShowStatus.PUBLISHED)));
        when(showtimeRepository.existsByShow_Id(1L)).thenReturn(true);

        assertThatThrownBy(() -> showService.delete(1L))
                .extracting("errorCode").isEqualTo(ErrorCode.SHOW_HAS_SHOWTIMES);
        verify(showRepository, never()).delete(any(Show.class));
    }

    @Test
    void findPublicShowtimes_convertsVietnamDayToUtcRange() {
        when(showRepository.findById(1L)).thenReturn(Optional.of(show(1L, ShowStatus.PUBLISHED)));
        when(showtimeRepository.findByShowAndPeriodAndCity(eq(1L), any(), any(), anyCollection(), eq("Hà Nội")))
                .thenReturn(List.of());

        showService.findPublicShowtimes(1L, LocalDate.parse("2026-10-20"), " Hà Nội ");

        // 00:00 ngày 20/10 giờ VN (UTC+7) = 17:00 ngày 19/10 UTC
        verify(showtimeRepository).findByShowAndPeriodAndCity(
                eq(1L),
                eq(Instant.parse("2026-10-19T17:00:00Z")),
                eq(Instant.parse("2026-10-20T17:00:00Z")),
                eq(java.util.EnumSet.of(ShowtimeStatus.SCHEDULED, ShowtimeStatus.ON_SALE)),
                eq("Hà Nội"));
    }

    @Test
    void findPublicShowtimes_draftShow_isHidden() {
        when(showRepository.findById(1L)).thenReturn(Optional.of(show(1L, ShowStatus.DRAFT)));

        assertThatThrownBy(() -> showService.findPublicShowtimes(1L, null, null))
                .extracting("errorCode").isEqualTo(ErrorCode.SHOW_NOT_FOUND);
    }

    private static ShowRequest request(String title, String slug) {
        return new ShowRequest(ShowType.MOVIE, title, slug, null, 120, null, null, null, null, null);
    }
}
