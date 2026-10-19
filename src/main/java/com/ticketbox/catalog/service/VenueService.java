package com.ticketbox.catalog.service;

import com.ticketbox.catalog.domain.Auditorium;
import com.ticketbox.catalog.domain.Venue;
import com.ticketbox.catalog.dto.VenueDtos.AuditoriumRequest;
import com.ticketbox.catalog.dto.VenueDtos.AuditoriumResponse;
import com.ticketbox.catalog.dto.VenueDtos.RowSpec;
import com.ticketbox.catalog.dto.VenueDtos.SeatLayoutRequest;
import com.ticketbox.catalog.dto.VenueDtos.SeatLayoutResponse;
import com.ticketbox.catalog.dto.VenueDtos.SeatResponse;
import com.ticketbox.catalog.dto.VenueDtos.VenueRequest;
import com.ticketbox.catalog.dto.VenueDtos.VenueResponse;
import com.ticketbox.catalog.repository.AuditoriumRepository;
import com.ticketbox.catalog.repository.SeatRepository;
import com.ticketbox.catalog.repository.VenueRepository;
import com.ticketbox.common.api.PageResponse;
import com.ticketbox.common.exception.BusinessException;
import com.ticketbox.common.exception.ErrorCode;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Quản lý địa điểm, phòng chiếu và sơ đồ ghế. */
@Service
public class VenueService {

    private static final int BATCH_SIZE = 50;
    private static final String INSERT_SEAT_SQL =
            "INSERT INTO seats (auditorium_id, row_label, seat_number, seat_type) VALUES (?, ?, ?, ?)";

    private final VenueRepository venueRepository;
    private final AuditoriumRepository auditoriumRepository;
    private final SeatRepository seatRepository;
    private final JdbcTemplate jdbcTemplate;

    public VenueService(VenueRepository venueRepository,
                        AuditoriumRepository auditoriumRepository,
                        SeatRepository seatRepository,
                        JdbcTemplate jdbcTemplate) {
        this.venueRepository = venueRepository;
        this.auditoriumRepository = auditoriumRepository;
        this.seatRepository = seatRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public VenueResponse createVenue(VenueRequest request) {
        Venue venue = Venue.create(request.name().trim(), request.city().trim(), request.address().trim());
        return VenueResponse.from(venueRepository.save(venue));
    }

    @Transactional(readOnly = true)
    public PageResponse<VenueResponse> listVenues(String city, Pageable pageable) {
        var page = (city == null || city.isBlank())
                ? venueRepository.findAll(pageable)
                : venueRepository.findByCityIgnoreCase(city.trim(), pageable);
        return PageResponse.from(page, VenueResponse::from);
    }

    @Transactional
    public AuditoriumResponse createAuditorium(AuditoriumRequest request) {
        Venue venue = venueRepository.findById(request.venueId())
                .orElseThrow(() -> new BusinessException(ErrorCode.VENUE_NOT_FOUND));
        // Trùng tên phòng trong cùng địa điểm: unique uk_auditoriums_venue_name -> DATA_CONFLICT
        Auditorium auditorium = auditoriumRepository.saveAndFlush(Auditorium.create(venue, request.name().trim()));
        return AuditoriumResponse.from(auditorium);
    }

    @Transactional(readOnly = true)
    public List<AuditoriumResponse> listAuditoriums(Long venueId) {
        if (!venueRepository.existsById(venueId)) {
            throw new BusinessException(ErrorCode.VENUE_NOT_FOUND);
        }
        return auditoriumRepository.findByVenueId(venueId).stream().map(AuditoriumResponse::from).toList();
    }

    /**
     * Tạo toàn bộ sơ đồ ghế của phòng trong một transaction bằng JDBC batch
     * (bảng dùng IDENTITY nên Hibernate không batch insert được; JdbcTemplate gửi 50 câu mỗi lượt).
     * Chỉ tạo một lần: sơ đồ đã có thì từ chối để không làm lệch các suất chiếu đã sinh ghế.
     */
    @Transactional
    public SeatLayoutResponse createSeatLayout(Long auditoriumId, SeatLayoutRequest request) {
        if (!auditoriumRepository.existsById(auditoriumId)) {
            throw new BusinessException(ErrorCode.AUDITORIUM_NOT_FOUND);
        }
        if (seatRepository.existsByAuditoriumId(auditoriumId)) {
            throw new BusinessException(ErrorCode.SEAT_LAYOUT_ALREADY_EXISTS);
        }
        ensureUniqueRows(request.rows());

        List<Object[]> rows = new ArrayList<>();
        for (RowSpec row : request.rows()) {
            for (int number = 1; number <= row.seatCount(); number++) {
                rows.add(new Object[]{auditoriumId, row.rowLabel(), number, row.seatTypeOrDefault().name()});
            }
        }
        jdbcTemplate.batchUpdate(INSERT_SEAT_SQL, rows, BATCH_SIZE, (ps, args) -> {
            ps.setLong(1, (Long) args[0]);
            ps.setString(2, (String) args[1]);
            ps.setInt(3, (Integer) args[2]);
            ps.setString(4, (String) args[3]);
        });
        return getSeatLayout(auditoriumId);
    }

    @Transactional(readOnly = true)
    public SeatLayoutResponse getSeatLayout(Long auditoriumId) {
        if (!auditoriumRepository.existsById(auditoriumId)) {
            throw new BusinessException(ErrorCode.AUDITORIUM_NOT_FOUND);
        }
        List<SeatResponse> seats = seatRepository.findByAuditoriumIdOrderByRowLabelAscSeatNumberAsc(auditoriumId)
                .stream().map(SeatResponse::from).toList();
        return new SeatLayoutResponse(auditoriumId, seats.size(), seats);
    }

    private static void ensureUniqueRows(List<RowSpec> rows) {
        Set<String> seen = new HashSet<>();
        for (RowSpec row : rows) {
            if (!seen.add(row.rowLabel())) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Hàng ghế " + row.rowLabel() + " bị lặp");
            }
        }
    }
}
