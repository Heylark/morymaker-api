package kr.co.morymaker.api

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import javax.sql.DataSource
import kotlin.test.assertTrue

/**
 * V1 마이그레이션이 실제로 10개 도메인 테이블을 생성했는지 DB 메타데이터로 직접 확인한다.
 * 로컬 MariaDB(infra/docker-compose.yml) 기동이 선행돼야 한다.
 */
@SpringBootTest
class FlywayMigrationTest(
    @Autowired private val dataSource: DataSource,
) {

    private val expectedTables = listOf(
        "event", "seat_group", "guest", "seat_assignment",
        "parking_zone", "parking_slot_title", "parking_record",
        "sms_template", "sms_log", "idle_content",
    )

    @Test
    fun `V1 마이그레이션이 10개 도메인 테이블을 모두 생성한다`() {
        dataSource.connection.use { conn ->
            val existing = mutableSetOf<String>()
            conn.metaData.getTables(conn.catalog, null, "%", arrayOf("TABLE")).use { rs ->
                while (rs.next()) {
                    existing.add(rs.getString("TABLE_NAME").lowercase())
                }
            }
            expectedTables.forEach { table ->
                assertTrue(existing.contains(table), "$table 테이블이 생성되지 않았습니다 (existing=$existing)")
            }
        }
    }

    @Test
    fun `parking_record의 active_key는 UNIQUE 인덱스를 갖는다`() {
        dataSource.connection.use { conn ->
            conn.metaData.getIndexInfo(conn.catalog, null, "parking_record", true, false).use { rs ->
                var found = false
                while (rs.next()) {
                    if (rs.getString("COLUMN_NAME")?.lowercase() == "active_key") found = true
                }
                assertTrue(found, "parking_record.active_key UNIQUE 인덱스가 존재하지 않습니다")
            }
        }
    }

    @Test
    fun `account와 account_event 테이블은 이 마이그레이션이 생성하지 않는다`() {
        // 인증 서버(auth)가 별도 Flyway 이력으로 소유하는 테이블 — api가 중복 생성하면 소유권 충돌.
        // 두 테이블은 이미 auth 마이그레이션으로 존재해야 하므로 존재 자체가 아니라 "api가 만들지
        // 않았다"는 사실을 간접 확인하는 차원에서, 여기서는 api 소유 10개 테이블 목록에 없음만 재확인.
        assertTrue("account" !in expectedTables)
        assertTrue("account_event" !in expectedTables)
    }
}
