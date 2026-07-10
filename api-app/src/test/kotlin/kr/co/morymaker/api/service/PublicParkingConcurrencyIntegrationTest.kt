package kr.co.morymaker.api.service

import kr.co.morymaker.api.application.parking.SlotOccupiedException
import kr.co.morymaker.api.application.port.`in`.CreateEventCommand
import kr.co.morymaker.api.application.port.`in`.EventUseCase
import kr.co.morymaker.api.application.port.`in`.ParkingRecordUseCase
import kr.co.morymaker.api.application.port.`in`.ParkingZoneUseCase
import kr.co.morymaker.api.application.port.`in`.PublicParkingUseCase
import kr.co.morymaker.api.application.port.`in`.RecordListQuery
import kr.co.morymaker.api.application.port.`in`.RegisterParkingResult
import kr.co.morymaker.api.application.port.`in`.SelfParkCommand
import kr.co.morymaker.api.application.port.`in`.ZoneCreateCommand
import kr.co.morymaker.api.domain.parking.ParkingRecord
import kr.co.morymaker.api.domain.parking.ParkingSlot
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
 * 공개 셀프 주차([PublicParkingUseCase.selfPark]) 동시성 실 DB 통합 테스트 —
 * [ParkingRecordConcurrencyIntegrationTest]와 동일 불변식(active_key UNIQUE 최종 방어)을
 * 공개 경로(가드-free 코어 `ParkingWriteSupport` 공유)에서도 검증한다.
 *
 * 준비 단계(행사·구획 생성)만 인증 컨텍스트가 필요하고, 실제 경합 대상 호출(selfPark)은
 * 무인증 상태(`SecurityContextHolder` 미설정)로 실행한다 — 공개 경로가 `EventScopeGuard` 없이도
 * 동일한 동시성 방어를 상속받는지 확인하는 것이 이 테스트의 핵심이다.
 */
@SpringBootTest
class PublicParkingConcurrencyIntegrationTest(
    @Autowired private val publicParkingUseCase: PublicParkingUseCase,
    @Autowired private val zoneUseCase: ParkingZoneUseCase,
    @Autowired private val eventUseCase: EventUseCase,
    @Autowired private val recordUseCase: ParkingRecordUseCase,
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
                name = "공개 셀프주차 동시성 테스트 행사", eventDate = null, place = null, type = null,
                bgColor = null, pointColor = null, titleColor = null, bodyColor = null, kv = null,
            ),
        ).id
        createdEventIds += id
        return id
    }

    @Test
    fun `빈 자리에 서로 다른 차량이 동시 셀프 등록하면 활성 기록은 항상 1건으로 수렴하고 최소 1건은 SLOT_OCCUPIED 경합을 받는다`() {
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(systemAdminJwt())
        val eid = createEvent()
        val zone = zoneUseCase.createZone(
            eid,
            ZoneCreateCommand(part1 = "지하 2층", part2 = "B구역", part3 = null, part4 = null, startNo = 1, slotCount = 5),
        )
        SecurityContextHolder.clearContext()

        val slotCode = ParkingSlot.slotCode(zone.id, 1)
        val threadCount = 2
        val ready = CountDownLatch(threadCount)
        val start = CountDownLatch(1)
        val results = Collections.synchronizedList(mutableListOf<Result<RegisterParkingResult>>())
        val pool = Executors.newFixedThreadPool(threadCount)

        repeat(threadCount) { i ->
            pool.submit {
                // 무인증 — 공개 경로는 SecurityContext 없이도 동작해야 한다(assertAccess 구조적 무의존).
                ready.countDown()
                start.await()
                val outcome = runCatching {
                    publicParkingUseCase.selfPark(
                        slotCode,
                        SelfParkCommand(plate = "34나000$i", vipName = null, phone = null, token = null),
                    )
                }
                results.add(outcome)
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
        assertTrue(occupied >= 1, "2-스레드 동시 방출에서 최소 1건은 active_key UNIQUE 경합(SLOT_OCCUPIED)을 받아야 한다")

        // 스케줄링과 무관하게 항상 참인 불변식 — 사후 해당 자리의 활성(주차중) 기록은 정확히 1건.
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(systemAdminJwt())
        val active = recordUseCase.listRecords(eid, RecordListQuery(zoneId = zone.id, status = ParkingRecord.STATUS_PARKED))
            .filter { it.zoneId == zone.id }
        assertEquals(1, active.size, "경합 후에도 자리당 활성 기록은 1건으로 수렴해야 한다(active_key 무결성)")
    }
}
