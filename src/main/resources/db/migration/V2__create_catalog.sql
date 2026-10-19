-- =====================================================================
-- V2: Module Catalog - phim/sự kiện, địa điểm, phòng, ghế, suất chiếu, giá
-- =====================================================================

-- Cần cho exclusion constraint chống trùng lịch phòng chiếu (kết hợp "=" và "&&")
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- Phim và sự kiện dùng chung một bảng, phân biệt bằng "type"
CREATE TABLE shows (
    id            BIGSERIAL    PRIMARY KEY,
    type          VARCHAR(20)  NOT NULL,
    title         VARCHAR(255) NOT NULL,
    slug          VARCHAR(255) NOT NULL,
    description   TEXT,
    duration_min  INT,
    age_rating    VARCHAR(5),
    poster_url    VARCHAR(500),
    trailer_url   VARCHAR(500),
    release_date  DATE,
    status        VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uk_shows_slug       UNIQUE (slug),
    CONSTRAINT ck_shows_type       CHECK (type IN ('MOVIE', 'CONCERT', 'THEATER', 'SPORT', 'OTHER')),
    CONSTRAINT ck_shows_status     CHECK (status IN ('DRAFT', 'PUBLISHED', 'ENDED')),
    CONSTRAINT ck_shows_age_rating CHECK (age_rating IS NULL OR age_rating IN ('P', 'K', 'T13', 'T16', 'T18')),
    CONSTRAINT ck_shows_duration   CHECK (duration_min IS NULL OR duration_min > 0)
);
CREATE INDEX idx_shows_type_status ON shows (type, status);

-- Rạp / nhà hát / sân vận động
CREATE TABLE venues (
    id          BIGSERIAL    PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    city        VARCHAR(100) NOT NULL,
    address     VARCHAR(500) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_venues_city ON venues (city);

-- Phòng chiếu / khu vực trong một địa điểm
CREATE TABLE auditoriums (
    id          BIGSERIAL    PRIMARY KEY,
    venue_id    BIGINT       NOT NULL REFERENCES venues (id),
    name        VARCHAR(100) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uk_auditoriums_venue_name UNIQUE (venue_id, name)
);

-- Sơ đồ ghế vật lý, cố định theo phòng
CREATE TABLE seats (
    id             BIGSERIAL   PRIMARY KEY,
    auditorium_id  BIGINT      NOT NULL REFERENCES auditoriums (id),
    row_label      VARCHAR(5)  NOT NULL,
    seat_number    INT         NOT NULL,
    seat_type      VARCHAR(20) NOT NULL DEFAULT 'STANDARD',
    active         BOOLEAN     NOT NULL DEFAULT TRUE,

    CONSTRAINT uk_seats_position UNIQUE (auditorium_id, row_label, seat_number),
    CONSTRAINT ck_seats_type     CHECK (seat_type IN ('STANDARD', 'VIP', 'COUPLE', 'WHEELCHAIR')),
    CONSTRAINT ck_seats_number   CHECK (seat_number > 0)
);

CREATE TABLE showtimes (
    id             BIGSERIAL   PRIMARY KEY,
    show_id        BIGINT      NOT NULL REFERENCES shows (id),
    auditorium_id  BIGINT      NOT NULL REFERENCES auditoriums (id),
    start_time     TIMESTAMPTZ NOT NULL,
    end_time       TIMESTAMPTZ NOT NULL,
    status         VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED',
    sale_open_at   TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_showtimes_status CHECK (status IN ('SCHEDULED', 'ON_SALE', 'CANCELLED', 'FINISHED')),
    CONSTRAINT ck_showtimes_time   CHECK (end_time > start_time),

    -- Database tự chặn 2 suất chiếu trùng giờ trong cùng một phòng (suất đã hủy thì bỏ qua)
    CONSTRAINT ex_showtimes_no_overlap EXCLUDE USING gist (
        auditorium_id WITH =,
        tstzrange(start_time, end_time) WITH &&
    ) WHERE (status <> 'CANCELLED')
);
CREATE INDEX idx_showtimes_show_start ON showtimes (show_id, start_time);
CREATE INDEX idx_showtimes_start      ON showtimes (start_time);

-- Giá vé theo loại ghế cho từng suất
CREATE TABLE ticket_prices (
    showtime_id  BIGINT        NOT NULL REFERENCES showtimes (id),
    seat_type    VARCHAR(20)   NOT NULL,
    price        NUMERIC(12,0) NOT NULL,

    CONSTRAINT pk_ticket_prices       PRIMARY KEY (showtime_id, seat_type),
    CONSTRAINT ck_ticket_prices_price CHECK (price >= 0),
    CONSTRAINT ck_ticket_prices_type  CHECK (seat_type IN ('STANDARD', 'VIP', 'COUPLE', 'WHEELCHAIR'))
);

COMMENT ON TABLE shows IS 'Phim và sự kiện (concert, kịch, thể thao...)';
COMMENT ON CONSTRAINT ex_showtimes_no_overlap ON showtimes IS 'Chống trùng lịch: một phòng không thể có 2 suất giao nhau về thời gian';
