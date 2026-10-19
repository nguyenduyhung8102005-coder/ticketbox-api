package com.ticketbox.catalog.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SlugGeneratorTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Lật Mặt 8: Vòng Tay Nắng | lat-mat-8-vong-tay-nang",
            "Đất Rừng Phương Nam      | dat-rung-phuong-nam",
            "  Mai  (2024)  !!        | mai-2024",
            "Avengers: Endgame        | avengers-endgame"
    })
    void slugify_removesVietnameseDiacriticsAndPunctuation(String title, String expected) {
        assertThat(SlugGenerator.slugify(title)).isEqualTo(expected);
    }

    @Test
    void slugify_onlySymbols_fallsBackToDefault() {
        assertThat(SlugGenerator.slugify("!!!")).isEqualTo("show");
        assertThat(SlugGenerator.slugify(null)).isEqualTo("show");
    }

    @Test
    void unique_appendsSuffixUntilFree() {
        Set<String> taken = Set.of("mai", "mai-2");

        assertThat(SlugGenerator.unique("mai", taken::contains)).isEqualTo("mai-3");
        assertThat(SlugGenerator.unique("lat-mat", taken::contains)).isEqualTo("lat-mat");
    }
}
