package com.ticketbox.catalog.service;

import com.ticketbox.catalog.domain.Show;
import com.ticketbox.catalog.domain.ShowStatus;
import com.ticketbox.catalog.domain.ShowType;
import com.ticketbox.catalog.domain.Showtime;
import com.ticketbox.catalog.domain.ShowtimeStatus;
import com.ticketbox.catalog.dto.ShowDtos.ShowDetailResponse;
import com.ticketbox.catalog.dto.ShowDtos.ShowRequest;
import com.ticketbox.catalog.dto.ShowDtos.ShowSummaryResponse;
import com.ticketbox.catalog.dto.ShowtimeDtos.PublicShowtimeResponse;
import com.ticketbox.catalog.repository.ShowRepository;
import com.ticketbox.catalog.repository.ShowSpecifications;
import com.ticketbox.catalog.repository.ShowtimeRepository;
import com.ticketbox.common.api.PageResponse;
import com.ticketbox.common.exception.BusinessException;
import com.ticketbox.common.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

@Service
public class ShowService {

    /** Lịch chiếu công khai chỉ hiện suất sắp/đang bán, ẩn suất đã hủy/kết thúc. */
    private static final Set<ShowtimeStatus> PUBLIC_SHOWTIME_STATUSES =
            EnumSet.of(ShowtimeStatus.SCHEDULED, ShowtimeStatus.ON_SALE);

    private final ShowRepository showRepository;
    private final ShowtimeRepository showtimeRepository;
    private final Clock clock;
    private final ZoneId businessZone;

    public ShowService(ShowRepository showRepository,
                       ShowtimeRepository showtimeRepository,
                       Clock clock,
                       @Value("${app.timezone:Asia/Ho_Chi_Minh}") String timezone) {
        this.showRepository = showRepository;
        this.showtimeRepository = showtimeRepository;
        this.clock = clock;
        this.businessZone = ZoneId.of(timezone);
    }

    // ===================== Public =====================

    /** Khách chỉ xem được show đã công bố (mặc định PUBLISHED); bản nháp DRAFT luôn bị ẩn. */
    @Transactional(readOnly = true)
    public PageResponse<ShowSummaryResponse> searchPublic(ShowType type, ShowStatus status, String keyword,
                                                          Pageable pageable) {
        if (status == ShowStatus.DRAFT) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Không thể lọc theo trạng thái DRAFT");
        }
        ShowStatus effective = status == null ? ShowStatus.PUBLISHED : status;
        return PageResponse.from(
                showRepository.findAll(ShowSpecifications.filter(type, effective, keyword), pageable),
                ShowSummaryResponse::from);
    }

    @Transactional(readOnly = true)
    public ShowDetailResponse getPublicBySlug(String slug) {
        return showRepository.findBySlug(slug)
                .filter(Show::isVisibleToPublic)
                .map(ShowDetailResponse::from)
                .orElseThrow(() -> new BusinessException(ErrorCode.SHOW_NOT_FOUND));
    }

    /**
     * Lịch chiếu của một show trong một ngày (theo giờ Việt Nam), lọc theo thành phố nếu có.
     * Ngày 20/10 giờ VN = [19/10 17:00Z, 20/10 17:00Z) giờ UTC trong DB.
     */
    @Transactional(readOnly = true)
    public List<PublicShowtimeResponse> findPublicShowtimes(Long showId, LocalDate date, String city) {
        Show show = showRepository.findById(showId)
                .filter(Show::isVisibleToPublic)
                .orElseThrow(() -> new BusinessException(ErrorCode.SHOW_NOT_FOUND));

        LocalDate day = date != null ? date : LocalDate.now(clock.withZone(businessZone));
        Instant from = day.atStartOfDay(businessZone).toInstant();
        Instant to = day.plusDays(1).atStartOfDay(businessZone).toInstant();

        List<Showtime> showtimes = (city == null || city.isBlank())
                ? showtimeRepository.findByShowAndPeriod(show.getId(), from, to, PUBLIC_SHOWTIME_STATUSES)
                : showtimeRepository.findByShowAndPeriodAndCity(show.getId(), from, to, PUBLIC_SHOWTIME_STATUSES,
                city.trim());
        return showtimes.stream().map(PublicShowtimeResponse::from).toList();
    }

    // ===================== Admin =====================

    @Transactional(readOnly = true)
    public PageResponse<ShowSummaryResponse> searchAdmin(ShowType type, ShowStatus status, String keyword,
                                                         Pageable pageable) {
        return PageResponse.from(
                showRepository.findAll(ShowSpecifications.filter(type, status, keyword), pageable),
                ShowSummaryResponse::from);
    }

    @Transactional(readOnly = true)
    public ShowDetailResponse getById(Long id) {
        return ShowDetailResponse.from(load(id));
    }

    @Transactional
    public ShowDetailResponse create(ShowRequest request) {
        String slug = resolveSlug(request, null);
        Show show = showRepository.save(Show.create(request.toDetails(), slug));
        return ShowDetailResponse.from(show);
    }

    @Transactional
    public ShowDetailResponse update(Long id, ShowRequest request) {
        Show show = load(id);
        show.update(request.toDetails(), resolveSlug(request, show));
        // flush để auditing cập nhật updated_at trước khi dựng response
        showRepository.flush();
        return ShowDetailResponse.from(show);
    }

    /** Chỉ xóa được show chưa có suất chiếu; có rồi thì chuyển sang ENDED thay vì xóa. */
    @Transactional
    public void delete(Long id) {
        Show show = load(id);
        if (showtimeRepository.existsByShow_Id(id)) {
            throw new BusinessException(ErrorCode.SHOW_HAS_SHOWTIMES);
        }
        showRepository.delete(show);
    }

    private Show load(Long id) {
        return showRepository.findById(id).orElseThrow(() -> new BusinessException(ErrorCode.SHOW_NOT_FOUND));
    }

    /**
     * Slug do admin nhập thì phải chưa bị dùng; bỏ trống thì sinh từ title và tự thêm hậu tố nếu trùng.
     * Khi cập nhật mà không gửi slug thì giữ slug cũ (link đã chia sẻ không bị hỏng).
     */
    private String resolveSlug(ShowRequest request, Show current) {
        String requested = request.slug();
        if (requested == null || requested.isBlank()) {
            return current != null
                    ? current.getSlug()
                    : SlugGenerator.unique(SlugGenerator.slugify(request.title()), showRepository::existsBySlug);
        }
        boolean unchanged = current != null && Objects.equals(current.getSlug(), requested);
        if (!unchanged && showRepository.existsBySlug(requested)) {
            throw new BusinessException(ErrorCode.SLUG_ALREADY_EXISTS);
        }
        return requested;
    }
}
