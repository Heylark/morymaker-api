package kr.co.morymaker.api.web

import jakarta.validation.Valid
import kr.co.morymaker.api.application.port.`in`.PublicKioskUseCase
import kr.co.morymaker.api.dto.ApiResponse
import kr.co.morymaker.api.dto.Meta
import kr.co.morymaker.api.dto.PublicAttendeeResponse
import kr.co.morymaker.api.dto.PublicKioskCheckinRequest
import kr.co.morymaker.api.dto.PublicKioskCheckinResponse
import kr.co.morymaker.api.dto.PublicParkingSearchResponse
import kr.co.morymaker.api.dto.toPublicAttendeeResponse
import kr.co.morymaker.api.dto.toPublicKioskCheckinResponse
import kr.co.morymaker.api.dto.toPublicParkingSearchResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * kiosk 공개 조회 API(REQ-0019, KIO-02·04·05) — 무인증 공개 서브라우트(D-D). `SecurityConfig`의
 * `/api/public` 하위 전체 permitAll이 자동 커버하고, `EventScopeInterceptor`는 `/api/events`
 * 하위만 매칭해 이 경로를 가로채지 않는다(prefix 상이 — 의도된 공개 표면, 게이트 우회 아님).
 *
 * `{eid}` = event.id 직접 재사용(eventCode capability 확립 패턴). rate limit은
 * `PublicRateLimitInterceptor`(`WebMvcConfig`가 개별 경로 등록)가 이름검색·주차검색 GET +
 * 체크인 POST 모두 적용한다(D-B).
 */
@RestController
@RequestMapping("/api/public/events/{eid}")
class PublicKioskController(
    private val kioskUseCase: PublicKioskUseCase,
) {

    // name은 required=false로 선언한다 — 누락 시 400으로 응답하도록 서비스 계층 require()
    // 단일 지점에 위임한다(LookupController와 동일 원칙 — 파라미터 자체 누락은
    // MissingServletRequestParameterException이 GlobalExceptionHandler catch-all에서 500으로
    // 새어나가는 함정을 피한다).
    @GetMapping("/attendees")
    fun searchAttendees(
        @PathVariable eid: String,
        @RequestParam(required = false) name: String?,
    ): ApiResponse<List<PublicAttendeeResponse>> {
        val result = kioskUseCase.searchAttendees(eid, name ?: "")
        return ApiResponse(
            data = result.items.map { it.toPublicAttendeeResponse() },
            meta = Meta(total = result.total, searchState = result.searchState),
        )
    }

    @PostMapping("/checkin")
    fun checkin(
        @PathVariable eid: String,
        @Valid @RequestBody request: PublicKioskCheckinRequest,
    ): ApiResponse<PublicKioskCheckinResponse> =
        ApiResponse(kioskUseCase.checkin(eid, request.guestId).toPublicKioskCheckinResponse())

    @GetMapping("/parking-search")
    fun searchParking(
        @PathVariable eid: String,
        @RequestParam(required = false) plateTail: String?,
    ): ApiResponse<List<PublicParkingSearchResponse>> {
        val result = kioskUseCase.searchParking(eid, plateTail ?: "")
        return ApiResponse(data = result.map { it.toPublicParkingSearchResponse() })
    }
}
