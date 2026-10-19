package com.ticketbox.catalog.dto;

import com.ticketbox.catalog.domain.AgeRating;
import com.ticketbox.catalog.domain.Show;
import com.ticketbox.catalog.domain.ShowStatus;
import com.ticketbox.catalog.domain.ShowType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;

public final class ShowDtos {

    private ShowDtos() {
    }

    /**
     * Dùng cho cả tạo mới (POST) và cập nhật toàn bộ (PUT).
     * slug bỏ trống thì tự sinh từ title; status bỏ trống: tạo mới là DRAFT, cập nhật thì giữ nguyên.
     */
    public record ShowRequest(
            @NotNull @Schema(example = "MOVIE") ShowType type,
            @NotBlank @Size(max = 255) @Schema(example = "Lật Mặt 8: Vòng Tay Nắng") String title,
            @Size(max = 255)
            @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$", message = "Slug chỉ gồm chữ thường, số và dấu gạch ngang")
            @Schema(example = "lat-mat-8-vong-tay-nang") String slug,
            String description,
            @Positive @Schema(example = "135") Integer durationMin,
            @Schema(example = "T13") AgeRating ageRating,
            @Size(max = 500) String posterUrl,
            @Size(max = 500) String trailerUrl,
            @Schema(example = "2026-10-20") LocalDate releaseDate,
            ShowStatus status) {

        public Show.ShowDetails toDetails() {
            return new Show.ShowDetails(type, title.trim(), description, durationMin, ageRating,
                    posterUrl, trailerUrl, releaseDate, status);
        }
    }

    public record ShowSummaryResponse(Long id, ShowType type, String title, String slug, Integer durationMin,
                                      AgeRating ageRating, String posterUrl, LocalDate releaseDate,
                                      ShowStatus status) {

        public static ShowSummaryResponse from(Show s) {
            return new ShowSummaryResponse(s.getId(), s.getType(), s.getTitle(), s.getSlug(), s.getDurationMin(),
                    s.getAgeRating(), s.getPosterUrl(), s.getReleaseDate(), s.getStatus());
        }
    }

    public record ShowDetailResponse(Long id, ShowType type, String title, String slug, String description,
                                     Integer durationMin, AgeRating ageRating, String posterUrl, String trailerUrl,
                                     LocalDate releaseDate, ShowStatus status, Instant createdAt,
                                     Instant updatedAt) {

        public static ShowDetailResponse from(Show s) {
            return new ShowDetailResponse(s.getId(), s.getType(), s.getTitle(), s.getSlug(), s.getDescription(),
                    s.getDurationMin(), s.getAgeRating(), s.getPosterUrl(), s.getTrailerUrl(), s.getReleaseDate(),
                    s.getStatus(), s.getCreatedAt(), s.getUpdatedAt());
        }
    }
}
