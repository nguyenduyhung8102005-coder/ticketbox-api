package com.ticketbox.catalog.domain;

import com.ticketbox.common.exception.BusinessException;
import com.ticketbox.common.exception.ErrorCode;
import com.ticketbox.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Một suất chiếu của một show trong một phòng, tại một khoảng thời gian [start, end). */
@Getter
@Entity
@Table(name = "showtimes")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Showtime extends BaseEntity {

    /** Các bước chuyển trạng thái hợp lệ. FINISHED và CANCELLED là trạng thái cuối. */
    private static final Map<ShowtimeStatus, Set<ShowtimeStatus>> TRANSITIONS = Map.of(
            ShowtimeStatus.SCHEDULED, EnumSet.of(ShowtimeStatus.ON_SALE, ShowtimeStatus.CANCELLED),
            ShowtimeStatus.ON_SALE, EnumSet.of(ShowtimeStatus.SCHEDULED, ShowtimeStatus.CANCELLED,
                    ShowtimeStatus.FINISHED),
            ShowtimeStatus.CANCELLED, EnumSet.noneOf(ShowtimeStatus.class),
            ShowtimeStatus.FINISHED, EnumSet.noneOf(ShowtimeStatus.class));

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "show_id", nullable = false)
    private Show show;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "auditorium_id", nullable = false)
    private Auditorium auditorium;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "end_time", nullable = false)
    private Instant endTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ShowtimeStatus status;

    @Column(name = "sale_open_at")
    private Instant saleOpenAt;

    public static Showtime create(Show show, Auditorium auditorium, Instant startTime, Instant endTime,
                                  ShowtimeStatus status, Instant saleOpenAt) {
        Showtime showtime = new Showtime();
        showtime.show = show;
        showtime.auditorium = auditorium;
        showtime.startTime = startTime;
        showtime.endTime = endTime;
        showtime.status = status;
        showtime.saleOpenAt = saleOpenAt;
        return showtime;
    }

    public void changeStatus(ShowtimeStatus target) {
        if (target == status) {
            return;
        }
        if (!TRANSITIONS.get(status).contains(target)) {
            throw new BusinessException(ErrorCode.SHOWTIME_STATUS_TRANSITION_INVALID,
                    "Không thể chuyển suất chiếu từ " + status + " sang " + target);
        }
        this.status = target;
    }

    /** Đang mở bán: trạng thái ON_SALE, đã tới giờ mở bán và suất chưa bắt đầu. */
    public boolean isOnSaleAt(Instant now) {
        return status == ShowtimeStatus.ON_SALE
                && (saleOpenAt == null || !now.isBefore(saleOpenAt))
                && now.isBefore(startTime);
    }
}
