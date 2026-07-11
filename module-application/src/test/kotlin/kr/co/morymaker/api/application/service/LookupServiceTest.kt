package kr.co.morymaker.api.application.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.co.morymaker.api.application.port.`in`.GuestListResult
import kr.co.morymaker.api.application.port.`in`.LookupResult
import kr.co.morymaker.api.application.security.EventScopeGuard
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * [LookupService] 단위 테스트 — [EventScopeGuard]/[LookupSearchSupport] 위임 가드만 검증한다.
 *
 * 검색 코어(이름 부분일치 ∪ 차량 뒷자리 매칭 + 좌석/주차 병기, §9-1) 자체 검증은 가드-free 추출
 * 이후 [LookupSearchSupportTest]로 이전됐다 — 공개 kiosk 경로와 공유하는 SSOT라 그쪽이 두 경로
 * 공통 동작의 단일 검증 지점이다(동작 변경 없음, 검증 자산 이전).
 */
class LookupServiceTest {

    private val eventScopeGuard = mockk<EventScopeGuard>()
    private val lookupSearchSupport = mockk<LookupSearchSupport>()
    private val service = LookupService(lookupSearchSupport, eventScopeGuard)

    @Test
    fun `lookup은 assertAccess 호출 후 LookupSearchSupport에 위임하고 결과를 그대로 반환한다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        val expected = LookupResult(items = emptyList(), total = 0, searchState = GuestListResult.SEARCH_STATE_NONE)
        every { lookupSearchSupport.search("ev1", "김진우") } returns expected

        val result = service.lookup("ev1", "김진우")

        assertEquals(expected, result)
        verify(exactly = 1) { eventScopeGuard.assertAccess("ev1") }
        verify(exactly = 1) { lookupSearchSupport.search("ev1", "김진우") }
    }
}
