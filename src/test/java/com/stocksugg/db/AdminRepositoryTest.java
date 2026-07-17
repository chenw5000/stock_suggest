package com.stocksugg.db;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminRepositoryTest {

    @Test
    void upsertCreatesAndUpdatesByKey() throws Exception {
        try (Database db = new Database("jdbc:h2:mem:admin_repo;DB_CLOSE_DELAY=-1")) {
            AdminRepository repository = new AdminRepository(db);

            Map<String, Object> created = repository.upsert("theme", "dark");
            assertEquals(1, created.get("id"));
            assertEquals("theme", created.get("key"));
            assertEquals("dark", created.get("value"));
            assertEquals(1, repository.findAll().size());

            Map<String, Object> updated = repository.upsert("theme", "light");
            assertEquals(1, updated.get("id"));
            assertEquals("light", updated.get("value"));
            assertEquals(1, repository.findAll().size());

            Map<String, Object> second = repository.upsert("page.size", "10");
            assertEquals(2, second.get("id"));
            assertEquals(2, repository.findAll().size());
            assertTrue(repository.findByKey("theme").isPresent());
        }
    }
}
