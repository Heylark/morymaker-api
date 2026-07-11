package kr.co.morymaker.api.service

import kr.co.morymaker.api.application.port.`in`.CheckinResult
import kr.co.morymaker.api.application.port.`in`.CreateEventCommand
import kr.co.morymaker.api.application.port.`in`.EventUseCase
import kr.co.morymaker.api.application.port.`in`.GuestUseCase
import kr.co.morymaker.api.application.port.`in`.PublicKioskUseCase
import kr.co.morymaker.api.application.port.`in`.RegisterGuestCommand
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
 * 공개 kiosk 체크인([PublicKioskUseCase.checkin]) 동시성 실 DB 통합 테스트 —
 * 무인 kiosk 더블탭이 조건부 UPDATE(`markAttendedIfNotAttended`)로 정확히 1건만 CHECKED_IN으로
 * 수렴하는지 검증한다([PublicParkingConcurrencyIntegrationTest]와 동일 컨벤션·불변식 스타일).
 *
 * 준비 단계(행사·참석자 생성)만 인증 컨텍스트가 필요하고, 실제 경합 대상 호출(checkin)은
 * 무인증 상태(`SecurityContextHolder` 미설정)로 실행한다 — 공개 경로가 `EventScopeGuard` 없이도
 * 동일한 동시성 방어를 상속받는지 확인하는 것이 이 테스트의 핵심이다. `@Transactional` 클래스
 * 레벨 대신 스레드 풀 경합을 실제로 관찰해야 하므로 `AfterEach` 수동 정리를 사용한다
 * (`PublicParkingConcurrencyIntegrationTest`와 동일 사유 — 클래스 트랜잭션은 병렬 스레드의
 * 별도 커넥션 커밋을 가려 경합 자체가 재현되지 않는다).
 */
@SpringBootTest
class PublicKioskCheckinConcurrencyIntegrationTest(
    @Autowired private val kioskUseCase: PublicKioskUseCase,
    @Autowired private val eventUseCase: EventUseCase,
    @Autowired private val guestUseCase: GuestUseCase,
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
                name = "kiosk 체크인 동시성 테스트 행사", eventDate = null, place = null, type = null,
                bgColor = null, pointColor = null, titleColor = null, bodyColor = null, kv = null,
            ),
        ).id
        createdEventIds += id
        return id
    }

    private fun registerGuest(eid: String): String = guestUseCase.registerGuest(
        eid,
        RegisterGuestCommand(name = "동시성 테스트 참석자", org = null, title = null, phone = null, plate = null, seatGroupId = null),
    ).id

    @Test
    fun `동일 guestId로 동시에 체크인하면 정확히 1건만 CHECKED_IN이고 나머지는 ALREADY_CHECKED_IN이다`() {
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(systemAdminJwt())
        val eid = createEvent()
        val gid = registerGuest(eid)
        SecurityContextHolder.clearContext()

        val threadCount = 5
        val ready = CountDownLatch(threadCount)
        val start = CountDownLatch(1)
        val results = Collections.synchronizedList(mutableListOf<CheckinResult>())
        val pool = Executors.newFixedThreadPool(threadCount)

        repeat(threadCount) {
            pool.submit {
                // 무인증 — 공개 경로는 SecurityContext 없이도 동작해야 한다(EventScopeGuard 구조적 무의존).
                ready.countDown()
                start.await()
                val result = kioskUseCase.checkin(eid, gid)
                results.add(result)
            }
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS), "모든 스레드가 대기 상태에 도달해야 한다")
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS), "모든 체크인 호출이 시간 내 종료돼야 한다")

        assertEquals(threadCount, results.size, "모든 호출은 예외 없이 응답해야 한다(멱등 — 실패가 아니라 ALREADY로 수렴)")
        val checkedIn = results.count { it.resultCode == CheckinResult.CHECKED_IN }
        val already = results.count { it.resultCode == CheckinResult.ALREADY_CHECKED_IN }
        assertEquals(1, checkedIn, "동시 더블탭에서 정확히 1건만 CHECKED_IN이어야 한다(조건부 UPDATE 직렬화)")
        assertEquals(threadCount - 1, already, "나머지는 모두 ALREADY_CHECKED_IN(멱등)이어야 한다")
    }
}
