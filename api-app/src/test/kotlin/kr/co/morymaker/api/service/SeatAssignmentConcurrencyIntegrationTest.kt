package kr.co.morymaker.api.service

import kr.co.morymaker.api.application.port.`in`.AssignmentEntry
import kr.co.morymaker.api.application.port.`in`.BulkAssignCommand
import kr.co.morymaker.api.application.port.`in`.CreateEventCommand
import kr.co.morymaker.api.application.port.`in`.EventUseCase
import kr.co.morymaker.api.application.port.`in`.RegisterGuestCommand
import kr.co.morymaker.api.application.port.`in`.SeatAssignmentUseCase
import kr.co.morymaker.api.application.port.`in`.SeatGroupCreateCommand
import kr.co.morymaker.api.application.port.`in`.SeatGroupUseCase
import kr.co.morymaker.api.application.port.`in`.GuestUseCase
import kr.co.morymaker.api.application.seat.SeatConflictException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 좌석 배정(§12-5) 동시성 실 DB 통합 테스트 — 배정 동시성 가드(M1)의 최종 방어선인
 * `uq_seatassign_guest` UNIQUE(guest_id)가 cross-group 1인 다좌석을 하드 차단하는지(b1), 동일
 * 그룹 동시 원자 교체(DELETE+INSERT)가 데드락 없이 결정적으로 수렴하는지(b2)를 검증한다(
 * 02-architect §4).
 *
 * `SeatAssignmentServiceTest`(mock)는 `DuplicateKeyException`을 직접 주입해 번역 경로만
 * 검증한다 — mock은 실제 DB 잠금·UNIQUE 경합을 우회하므로(Tester 합리화 방지 표) 이 파일이
 * 실제 두 개 이상의 **독립 물리 트랜잭션**(별도 스레드 → 별도 커넥션풀 커넥션)으로 경합을
 * 재현한다. `ParkingRecordConcurrencyIntegrationTest`와 동일 원칙 — 클래스 레벨
 * `@Transactional`을 쓰지 않는다(자동 롤백이 실제 커밋 경합을 마스킹하기 때문).
 */
@SpringBootTest
class SeatAssignmentConcurrencyIntegrationTest(
    @Autowired private val assignmentUseCase: SeatAssignmentUseCase,
    @Autowired private val groupUseCase: SeatGroupUseCase,
    @Autowired private val guestUseCase: GuestUseCase,
    @Autowired private val eventUseCase: EventUseCase,
    @Autowired private val dataSource: DataSource,
) {

    private val createdEventIds = mutableListOf<String>()

    @AfterEach
    fun cleanup() {
        SecurityContextHolder.clearContext()
        dataSource.connection.use { conn ->
            createdEventIds.forEach { id ->
                conn.prepareStatement("DELETE FROM event WHERE id = ?").use { stmt ->
                    stmt.setString(1, id)
                    stmt.executeUpdate()
                }
            }
        }
        createdEventIds.clear()
    }

    private fun systemAdminJwt(): Jwt = Jwt.withTokenValue("dummy-token")
        .header("alg", "RS256")
        .claim("iss", "http://localhost:30000")
        .claim("roles", listOf("SYSTEM_ADMIN"))
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(3600))
        .build()

    private fun asSystemAdmin(block: () -> Unit) {
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(systemAdminJwt())
        try {
            block()
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    private fun createEvent(): String {
        var id = ""
        asSystemAdmin {
            id = eventUseCase.createEvent(
                CreateEventCommand(
                    name = "좌석 동시성 테스트 행사", eventDate = null, place = null, type = null,
                    bgColor = null, pointColor = null, titleColor = null, bodyColor = null, kv = null,
                ),
            ).id
        }
        createdEventIds += id
        return id
    }

    private fun createGuest(eid: String, name: String): String {
        var id = ""
        asSystemAdmin {
            id = guestUseCase.registerGuest(
                eid,
                RegisterGuestCommand(name = name, org = null, title = null, phone = null, plate = null, seatGroupId = null),
            ).id
        }
        return id
    }

    private fun createGroup(eid: String, label: String, numbering: Boolean): Int {
        var groupNo = 0
        asSystemAdmin {
            groupNo = groupUseCase.createGroup(eid, SeatGroupCreateCommand(label = label, numbering = numbering)).groupNo
        }
        return groupNo
    }

    // ── (b1) cross-group 동일 guest 동시 배정 — 1인 다좌석 하드 차단 ──────

    @Test
    fun `서로 다른 두 그룹에 같은 참석자를 동시 배정하면 정확히 1건만 성공하고 나머지는 SEAT_CONFLICT를 받는다`() {
        val eid = createEvent()
        val groupNoA = createGroup(eid, "A열", numbering = true)
        val groupNoB = createGroup(eid, "B열", numbering = true)
        val guestId = createGuest(eid, "동시성 참석자")

        val threadCount = 2
        val ready = CountDownLatch(threadCount)
        val start = CountDownLatch(1)
        val results = Collections.synchronizedList(mutableListOf<Result<Any>>())
        val pool = Executors.newFixedThreadPool(threadCount)
        val targets = listOf(groupNoA, groupNoB)

        repeat(threadCount) { i ->
            pool.submit {
                asSystemAdmin {
                    ready.countDown()
                    start.await()
                    val outcome = runCatching {
                        assignmentUseCase.replaceAssignments(
                            eid,
                            BulkAssignCommand(groupNo = targets[i], assignments = listOf(AssignmentEntry(ord = 1, guestId = guestId))),
                        )
                    }
                    results.add(outcome)
                }
            }
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS), "모든 스레드가 대기 상태에 도달해야 한다")
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS), "모든 배정 호출이 시간 내 종료돼야 한다")

        val successes = results.count { it.isSuccess }
        val conflicts = results.count { it.exceptionOrNull() is SeatConflictException }
        val unexpected = results.filter { it.isFailure && it.exceptionOrNull() !is SeatConflictException }

        assertTrue(unexpected.isEmpty(), "SeatConflictException 외 예외는 없어야 한다: $unexpected")
        assertEquals(threadCount, successes + conflicts, "모든 호출은 성공 또는 SEAT_CONFLICT 중 하나여야 한다")
        assertEquals(1, successes, "cross-group 동시 배정은 정확히 1건만 성공해야 한다(1인 다좌석 하드 차단)")

        // 스케줄링과 무관하게 항상 참인 불변식 — 사후 해당 참석자의 배정은 event 전체에서 정확히 1건.
        val active = dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT COUNT(*) FROM seat_assignment WHERE event_id = ? AND guest_id = ?").use { stmt ->
                stmt.setString(1, eid)
                stmt.setString(2, guestId)
                stmt.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
            }
        }
        assertEquals(1, active, "경합 후에도 참석자당 활성 배정은 1건으로 수렴해야 한다(uq_seatassign_guest 무결성)")
    }

    // ── (b2) 동일 그룹 동시 원자 교체 — 데드락 0, 결정적 수렴 ─────────────

    @Test
    fun `동일 그룹에 서로 다른 유효 배정 세트를 동시에 여러 번 교체해도 데드락 없이 하나의 세트로 결정적으로 수렴한다`() {
        val eid = createEvent()
        val groupNo = createGroup(eid, "A열", numbering = true)
        val g1 = createGuest(eid, "참석자1")
        val g2 = createGuest(eid, "참석자2")
        val g3 = createGuest(eid, "참석자3")

        // 멤버 구성은 동일(g1·g2·g3)하되 ord 순열만 다른 유효 세트 3종 — "누가 이기든 유효한 최종
        // 상태"라는 last-writer-wins 수용 전제(02-architect §4 "(a) 좌석 중복 점유 처리")를
        // 재현한다.
        val variants = listOf(
            listOf(AssignmentEntry(1, g1), AssignmentEntry(2, g2), AssignmentEntry(3, g3)),
            listOf(AssignmentEntry(1, g3), AssignmentEntry(2, g2), AssignmentEntry(3, g1)),
            listOf(AssignmentEntry(1, g2), AssignmentEntry(2, g1), AssignmentEntry(3, g3)),
        )

        val threadCount = 6
        val ready = CountDownLatch(threadCount)
        val start = CountDownLatch(1)
        val results = Collections.synchronizedList(mutableListOf<Result<Any>>())
        val pool = Executors.newFixedThreadPool(threadCount)

        repeat(threadCount) { i ->
            pool.submit {
                asSystemAdmin {
                    ready.countDown()
                    start.await()
                    val outcome = runCatching {
                        assignmentUseCase.replaceAssignments(
                            eid,
                            BulkAssignCommand(groupNo = groupNo, assignments = variants[i % variants.size]),
                        )
                    }
                    results.add(outcome)
                }
            }
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS), "모든 스레드가 대기 상태에 도달해야 한다")
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS), "모든 교체 호출이 시간 내 종료돼야 한다")

        val unexpected = results.filter { it.isFailure && it.exceptionOrNull() !is SeatConflictException }
        assertTrue(unexpected.isEmpty(), "SeatConflictException(락 경합 번역) 외 예외는 없어야 한다(데드락 0): $unexpected")
        assertTrue(results.any { it.isSuccess }, "최소 1건은 성공해야 한다")

        // 사후 상태 — 정확히 3행, ord {1,2,3} 중복 없이, 멤버는 variants 중 하나와 정확히 일치.
        val finalRows = dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT ord, guest_id FROM seat_assignment WHERE seat_group_id = (SELECT id FROM seat_group WHERE event_id = ? AND group_no = ?) ORDER BY ord ASC").use { stmt ->
                stmt.setString(1, eid)
                stmt.setInt(2, groupNo)
                stmt.executeQuery().use { rs ->
                    val rows = mutableListOf<Pair<Int, String>>()
                    while (rs.next()) rows.add(rs.getInt("ord") to rs.getString("guest_id"))
                    rows
                }
            }
        }
        assertEquals(3, finalRows.size, "최종 상태는 3행이어야 한다(중복·유실 없음)")
        assertEquals(listOf(1, 2, 3), finalRows.map { it.first }, "ord는 1..3 연속·중복 없이 정렬돼야 한다")
        val finalGuestIds = finalRows.map { it.second }.toSet()
        assertTrue(
            variants.any { variant -> variant.map { it.guestId }.toSet() == finalGuestIds },
            "최종 멤버 구성은 시도한 유효 세트 중 하나와 정확히 일치해야 한다(결정적 수렴): $finalGuestIds",
        )
    }
}
