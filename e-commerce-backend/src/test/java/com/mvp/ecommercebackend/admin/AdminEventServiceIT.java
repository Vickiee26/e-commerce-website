package com.mvp.ecommercebackend.admin;

import com.mvp.ecommercebackend.admin.entity.AdminEventType;
import com.mvp.ecommercebackend.admin.entity.AdminTargetType;
import com.mvp.ecommercebackend.auth.entity.User;
import com.mvp.ecommercebackend.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AdminEventServiceIT extends AbstractIntegrationTest {

    @Autowired
    private AdminEventService adminEventService;

    @Test
    void recordsTheActorTheActionAndTheTarget() {
        User admin = testData.createAdmin("auditor@example.com", "correct-horse-battery");
        UUID targetId = UUID.randomUUID();

        adminEventService.record(admin.getId(), AdminEventType.PRODUCT_ARCHIVED,
                AdminTargetType.PRODUCT, targetId, "Discontinued");

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT actor_user_id, action, target_type, target_id, detail FROM admin_events");
        assertThat(row.get("actor_user_id")).isEqualTo(admin.getId());
        assertThat(row.get("action")).isEqualTo("PRODUCT_ARCHIVED");
        assertThat(row.get("target_type")).isEqualTo("PRODUCT");
        assertThat(row.get("target_id")).isEqualTo(targetId);
        assertThat(row.get("detail")).isEqualTo("Discontinued");
    }

    /** A detail longer than the column is truncated rather than throwing at the database. */
    @Test
    void truncatesAnOverlongDetail() {
        User admin = testData.createAdmin("auditor@example.com", "correct-horse-battery");

        adminEventService.record(admin.getId(), AdminEventType.PRODUCT_UPDATED,
                AdminTargetType.PRODUCT, UUID.randomUUID(), "x".repeat(1500));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT length(detail) FROM admin_events", Integer.class)).isEqualTo(1000);
    }
}
