package kr.co.morymaker.api.service

import kr.co.morymaker.api.application.port.`in`.CreateEventCommand
import kr.co.morymaker.api.application.port.`in`.EventUseCase
import kr.co.morymaker.api.application.port.`in`.GuestImportRow
import kr.co.morymaker.api.application.port.`in`.GuestSearchCommand
import kr.co.morymaker.api.application.port.`in`.GuestUseCase
import kr.co.morymaker.api.application.port.`in`.RegisterGuestCommand
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import java.time.Instant
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 엑셀 병합(§4-5·4-6, D1) 실 DB 통합 테스트 — `GuestExcelParser`(MultipartFile 파싱)를 우회해
 * [GuestUseCase]를 파싱된 [GuestImportRow] 리스트로 직접 호출한다. `GuestServiceTest`(mock)가
 * 우회하는 실 SQL 경로(resultMap 매핑·매칭 조회·트랜잭션 경계)가 이 파일의 검증 대상이다
 * (`~/.claude/rules-on-demand/anti-rationalization.md` Tester 절 — mock TC는 SQL을 검증하지 않는다).
 *
 * HTTP/MockMvc를 거치지 않으므로 `EventScopeGuardAdapterTest`와 동일하게 SecurityContext에
 * JWT를 직접 주입한다(SYSTEM_ADMIN — 행사 스코프 우회로 단순화. cross-tenant 자체는
 * `GuestControllerTest`·`CheckinControllerTest`가 이미 검증했다).
 *
 * 클래스 레벨 `@Transactional`을 쓰지 않는다 — 부분 실패 롤백 TC가 "실패 전 insert가 실제로
 * 커밋되지 않았는가"를 검증하려면 각 서비스 호출이 독립된 물리 트랜잭션(진짜 commit/rollback)
 * 이어야 한다(테스트를 감싸는 외부 트랜잭션과 묶으면 같은 커넥션 안에서 롤백 전 insert가 계속
 * 보여 검증이 무의미해진다). 대신 각 테스트가 만든 event를 `@AfterEach`에서 직접 JDBC로
 * 삭제한다(guest는 `fk_guest_event ON DELETE CASCADE`로 함께 정리된다).
 */
@SpringBootTest
class GuestImportIntegrationTest(
    @Autowired private val guestUseCase: GuestUseCase,
    @Autowired private val eventUseCase: EventUseCase,
    @Autowired private val dataSource: DataSource,
) {

    private val createdEventIds = mutableListOf<String>()

    @BeforeEach
    fun authenticateAsSystemAdmin() {
        val jwt = Jwt.withTokenValue("dummy-token")
            .header("alg", "RS256")
            .claim("iss", "http://localhost:30000")
            .claim("roles", listOf("SYSTEM_ADMIN"))
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(3600))
            .build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
    }

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

    private fun createEvent(name: String = "import 통합 테스트 행사"): String {
        val id = eventUseCase.createEvent(
            CreateEventCommand(
                name = name, eventDate = null, place = null, type = null,
                bgColor = null, pointColor = null, titleColor = null, bodyColor = null, kv = null,
            ),
        ).id
        createdEventIds += id
        return id
    }

    private fun row(rowNumber: Int, name: String?, phone: String? = null) =
        GuestImportRow(rowNumber, name, org = null, title = null, phone = phone, plate = null, seatGroupLabel = null)

    @Test
    fun `previewImport는 실 DB를 변경하지 않는다`() {
        val eid = createEvent()
        guestUseCase.registerGuest(eid, RegisterGuestCommand("김진우", null, null, "010-1234-5678", null, null, "사전"))

        val before = guestUseCase.listGuests(eid, GuestSearchCommand()).total
        guestUseCase.previewImport(eid, listOf(row(2, "박서연", "010-9999-0000")))
        val after = guestUseCase.listGuests(eid, GuestSearchCommand()).total

        assertEquals(1, before)
        assertEquals(before, after)
    }

    @Test
    fun `confirmImport는 phone 매칭 기존 참석자의 token을 보존한 채 실 DB에 필드를 갱신한다`() {
        val eid = createEvent()
        val registered = guestUseCase.registerGuest(
            eid, RegisterGuestCommand("김진우", "구소속", null, "010-1234-5678", null, null, "사전"),
        )

        guestUseCase.confirmImport(eid, listOf(row(2, "김진우", "010-1234-5678").copy(org = "새소속")))

        val updated = guestUseCase.getGuest(eid, registered.id)
        assertEquals(registered.token, updated.token)
        assertEquals("새소속", updated.org)
    }

    @Test
    fun `confirmImport는 phone 없는 행을 이름이 같아도 신규로 분류하고 매칭 못한 기존 참석자는 취소로 전환한다`() {
        val eid = createEvent()
        guestUseCase.registerGuest(eid, RegisterGuestCommand("김진우", null, null, "010-1234-5678", null, null, "사전"))

        val result = guestUseCase.confirmImport(eid, listOf(row(2, "김진우", phone = null)))

        assertEquals(1, result.newCount)
        assertEquals(0, result.updatedCount)
        assertEquals(1, result.cancelledCount)
        assertEquals(2, guestUseCase.listGuests(eid, GuestSearchCommand(includeCancelled = true)).total)
    }

    @Test
    fun `confirmImport는 배치 중 한 행이라도 DB 제약을 위반하면 전체를 롤백한다`() {
        val eid = createEvent()
        val overlong = "가".repeat(61) // guest.name VARCHAR(60) 초과 — STRICT_TRANS_TABLES에서 오류
        val rows = listOf(row(2, "정상행"), row(3, overlong))

        assertFailsWith<Exception> { guestUseCase.confirmImport(eid, rows) }

        val total = guestUseCase.listGuests(eid, GuestSearchCommand(includeCancelled = true)).total
        assertEquals(0, total)
    }
}
