package kr.co.morymaker.api.service

import kr.co.morymaker.api.application.parking.SlotOccupiedException
import kr.co.morymaker.api.application.port.`in`.CreateEventCommand
import kr.co.morymaker.api.application.port.`in`.EventUseCase
import kr.co.morymaker.api.application.port.`in`.ParkingRecordUseCase
import kr.co.morymaker.api.application.port.`in`.ParkingZoneUseCase
import kr.co.morymaker.api.application.port.`in`.RecordListQuery
import kr.co.morymaker.api.application.port.`in`.RegisterParkingCommand
import kr.co.morymaker.api.application.port.`in`.RegisterParkingResult
import kr.co.morymaker.api.application.port.`in`.ZoneCreateCommand
import kr.co.morymaker.api.domain.parking.ParkingRecord
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
 * 등록 코어(§6-6) 동시성 실 DB 통합 테스트 — 빈 자리(케이스 E)에 서로 다른 차량이 동시 최초
 * 등록을 시도할 때 `active_key` UNIQUE(uq_precord_active)가 최종 방어선으로 동작하는지
 * 검증한다(02-architect §4-1 "동시성(최종 방어)" · ADR-P5).
 *
 * `ParkingRecordServiceTest`(mock)는 `DuplicateKeyException`을 직접 주입해 번역 경로만
 * 검증한다 — mock은 실제 DB 잠금·UNIQUE 경합을 우회하므로(Tester 합리화 방지 표) 이 파일이
 * 실제 두 개의 **독립 물리 트랜잭션**(별도 스레드 → 별도 커넥션풀 커넥션)으로 경합을 재현한다.
 * 클래스 레벨 `@Transactional`을 쓰지 않는다 — 각 스레드의 `register()` 호출이 실제 커밋/롤백을
 * 거쳐야 UNIQUE 충돌이 재현된다(`GuestImportIntegrationTest`와 동일 원칙).
 *
 * 스케줄링에 따라 두 갈래로 갈릴 수 있다 — ① 진짜 경합(둘 다 활성기록 null을 보고 insert 시도)이면
 * 한쪽이 `SlotOccupiedException`, ② 한쪽이 커밋을 먼저 마치면 늦은 쪽은 승계(SUPERSEDED)로
 * 정상 분기된다. 두 갈래 모두에서 항상 참인 불변식(사후 활성 기록 1건)과, 2-스레드 동시 방출로
 * 사실상 보장되는 "최소 1건 SLOT_OCCUPIED 경합 발생"을 함께 검증한다.
 *
 * ⚠️ v1 이력(2026-07-09, 3회 재현) — 당시 `selectActiveForUpdate`의 `SELECT ... FOR UPDATE`가
 * `idx_precord_event_status(event_id, status)` 세컨더리 인덱스를 스캔했는데, 대상 슬롯에 활성
 * 기록이 0건인 케이스 E에서 InnoDB REPEATABLE READ 하 갭 락(팬텀 방지)이 걸려 두 트랜잭션이
 * 같은 갭을 동시에 잠근 뒤 각자 INSERT를 시도해 `DeadlockLoserDataAccessException`으로 500이
 * 발생했다. ADR-P1 v2(FOR UPDATE 제거 + `PessimisticLockingFailureException` 번역 확장)로
 * 재설계한 뒤 이 테스트는 **PASS**한다 — 재현 절차는 v1과 동일(2-스레드 동시 방출)이며, v2에서는
 * `selectActiveBySlot`(잠금 없는 단순 조회)이 갭 락을 만들지 않아 후착이 깔끔한
 * `DuplicateKeyException`(`uq_precord_active` 위반)만 받고 `SlotOccupiedException`(409)으로
 * 정상 번역된다(2026-07-09 재검증, 반복 실행 6/6 PASS로 재현율 확인). 상세는
 * `docs/plans/2026-07-09_api-parking/04-test-result.md` §3(v1 원인) 및 `02-architect.md` §9
 * ADR-P1 v2 참조.
 */
@SpringBootTest
class ParkingRecordConcurrencyIntegrationTest(
    @Autowired private val recordUseCase: ParkingRecordUseCase,
    @Autowired private val zoneUseCase: ParkingZoneUseCase,
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

    private fun createEvent(): String {
        val id = eventUseCase.createEvent(
            CreateEventCommand(
                name = "동시성 테스트 행사", eventDate = null, place = null, type = null,
                bgColor = null, pointColor = null, titleColor = null, bodyColor = null, kv = null,
            ),
        ).id
        createdEventIds += id
        return id
    }

    @Test
    fun `빈 자리에 서로 다른 차량이 동시 최초 등록하면 활성 기록은 항상 1건으로 수렴하고 최소 1건은 SLOT_OCCUPIED 경합을 받는다`() {
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(systemAdminJwt())
        val eid = createEvent()
        val zone = zoneUseCase.createZone(
            eid,
            ZoneCreateCommand(part1 = "지하 2층", part2 = "A구역", part3 = null, part4 = null, startNo = 1, slotCount = 5),
        )
        SecurityContextHolder.clearContext()

        val slotSig = "지하 2층·A구역·1"
        val threadCount = 2
        val ready = CountDownLatch(threadCount)
        val start = CountDownLatch(1)
        val results = Collections.synchronizedList(mutableListOf<Result<RegisterParkingResult>>())
        val pool = Executors.newFixedThreadPool(threadCount)

        repeat(threadCount) { i ->
            pool.submit {
                SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(systemAdminJwt())
                ready.countDown()
                start.await()
                val outcome = runCatching {
                    recordUseCase.register(
                        eid,
                        RegisterParkingCommand(
                            slotSig = slotSig, zoneId = zone.id, plate = "12가000$i",
                            phone = null, vipName = null, registeredBy = "요원",
                        ),
                    )
                }
                results.add(outcome)
                SecurityContextHolder.clearContext()
            }
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS), "모든 스레드가 대기 상태에 도달해야 한다")
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS), "모든 등록 호출이 시간 내 종료돼야 한다")

        val successes = results.count { it.isSuccess }
        val occupied = results.count { it.exceptionOrNull() is SlotOccupiedException }
        val unexpected = results.filter { it.isFailure && it.exceptionOrNull() !is SlotOccupiedException }

        assertTrue(unexpected.isEmpty(), "SlotOccupiedException 외 예외는 없어야 한다: $unexpected")
        assertEquals(threadCount, successes + occupied, "모든 호출은 성공 또는 SLOT_OCCUPIED 경합 중 하나여야 한다")
        assertTrue(occupied >= 1, "6-스레드 동시 방출에서 최소 1건은 active_key UNIQUE 경합(SLOT_OCCUPIED)을 받아야 한다")

        // 스케줄링과 무관하게 항상 참인 불변식 — 사후 해당 자리의 활성(주차중) 기록은 정확히 1건.
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(systemAdminJwt())
        val active = recordUseCase.listRecords(eid, RecordListQuery(zoneId = zone.id, status = ParkingRecord.STATUS_PARKED))
            .filter { it.slotSig == slotSig }
        assertEquals(1, active.size, "경합 후에도 자리당 활성 기록은 1건으로 수렴해야 한다(active_key 무결성)")
    }
}
