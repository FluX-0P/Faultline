package dev.faultline.cli.run;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SeedFileTest {
    @Test
    void aSeedFileGetsTheRunsSeedSoItsTestDataMatchesTheRequests() {
        String sql = "insert into cart (user_id) values ('00000000-0000-0000-0001-${seed:12}'), ('u-${seed}')";

        assertThat(Attempt.withSeed(sql, 319008L)).isEqualTo("insert into cart (user_id) values ('00000000-0000-0000-0001-000000319008'), ('u-319008')");
        // ${seq} is the request's, not the run's: it stays
        assertThat(Attempt.withSeed("-- ${seq:4}", 42L)).isEqualTo("-- ${seq:4}");
    }
}
