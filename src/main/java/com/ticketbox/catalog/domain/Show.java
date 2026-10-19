package com.ticketbox.catalog.domain;

import com.ticketbox.common.persistence.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/** Phim hoặc sự kiện (concert, kịch, thể thao...). */
@Getter
@Entity
@Table(name = "shows")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Show extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private ShowType type;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "slug", nullable = false)
    private String slug;

    @Column(name = "description")
    private String description;

    @Column(name = "duration_min")
    private Integer durationMin;

    @Enumerated(EnumType.STRING)
    @Column(name = "age_rating")
    private AgeRating ageRating;

    @Column(name = "poster_url")
    private String posterUrl;

    @Column(name = "trailer_url")
    private String trailerUrl;

    @Column(name = "release_date")
    private LocalDate releaseDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ShowStatus status;

    public static Show create(ShowDetails details, String slug) {
        Show show = new Show();
        show.slug = slug;
        show.status = ShowStatus.DRAFT;
        show.apply(details);
        return show;
    }

    public void update(ShowDetails details, String slug) {
        this.slug = slug;
        apply(details);
    }

    private void apply(ShowDetails d) {
        this.type = d.type();
        this.title = d.title();
        this.description = d.description();
        this.durationMin = d.durationMin();
        this.ageRating = d.ageRating();
        this.posterUrl = d.posterUrl();
        this.trailerUrl = d.trailerUrl();
        this.releaseDate = d.releaseDate();
        if (d.status() != null) {
            this.status = d.status();
        }
    }

    public boolean isVisibleToPublic() {
        return status != ShowStatus.DRAFT;
    }

    /** Dữ liệu sửa được của Show (tách khỏi DTO để entity không phụ thuộc tầng web). */
    public record ShowDetails(ShowType type, String title, String description, Integer durationMin,
                              AgeRating ageRating, String posterUrl, String trailerUrl,
                              LocalDate releaseDate, ShowStatus status) {
    }
}
