package com.stocksugg.db;

import com.stocksugg.stock.TuningParams;
import org.junit.jupiter.api.Test;

import java.sql.Statement;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TuningParamsRepositoryTest {

    @Test
    void findByIdReturnsNumDatePoint() throws Exception {
        try (Database db = new Database("jdbc:h2:mem:tuning_repo;DB_CLOSE_DELAY=-1");
             Statement stmt = db.connection().createStatement()) {
            // Seed may insert DEFAULT-40 as id=1; add explicit id=2 with lookback 20.
            stmt.executeUpdate("""
                    INSERT INTO tuning_params (
                        id, num_date_point, ma5_weight, ma10_weight, ma20_weight, ma50_weight,
                        ma200_weight, rsi_weight, cmo_weight, cmf_weight, name, description
                    ) VALUES (
                        2, 20, 1, 1, 1, 1, 1, 1, 1, 1, 'DATA-20', '20-bar lookback'
                    )
                    """);

            TuningParamsRepository repository = new TuningParamsRepository(db);

            Optional<TuningParams> baseline = repository.findById(1);
            assertTrue(baseline.isPresent());
            assertEquals(40, baseline.get().lookbackBars());

            TuningParams shortLookback = repository.requireById(2);
            assertEquals(20, shortLookback.numDatePoint());
            assertEquals(20, shortLookback.lookbackBars());
            assertEquals("DATA-20", shortLookback.name());

            assertEquals(2, repository.findAll().size());
            assertEquals("DEFAULT-40", repository.findAll().get(0).name());
        }
    }
}
