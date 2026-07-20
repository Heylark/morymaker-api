package kr.co.morymaker.api.web

import jakarta.validation.Valid
import kr.co.morymaker.api.application.port.`in`.PublicKioskUseCase
import kr.co.morymaker.api.dto.ApiResponse
import kr.co.morymaker.api.dto.Meta
import kr.co.morymaker.api.dto.PublicAttendeeResponse
import kr.co.morymaker.api.dto.PublicEventBrandingResponse
import kr.co.morymaker.api.dto.PublicKioskCheckinRequest
import kr.co.morymaker.api.dto.PublicKioskCheckinResponse
import kr.co.morymaker.api.dto.PublicParkingSearchResponse
import kr.co.morymaker.api.dto.toPublicAttendeeResponse
import kr.co.morymaker.api.dto.toPublicEventBrandingResponse
import kr.co.morymaker.api.dto.toPublicKioskCheckinResponse
import kr.co.morymaker.api.dto.toPublicParkingSearchResponse
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * kiosk 공개 조회 API(KIO-02·04·05 + 브랜딩 조회) — 무인증 공개 서브라우트. `SecurityConfig`의
 * `/public` 하위 전체 permitAll이 자동 커버하고, `EventScopeInterceptor`는 `/events`
 * 하위만 매칭해 이 경로를 가로채지 않는다(prefix 상이 — 의도된 공개 표면, 게이트 우회 아님).
 *
 * `{eid}` = event.id 직접 재사용(eventCode capability 확립 패턴). rate limit은
 * `PublicRateLimitInterceptor`(`WebMvcConfig`가 개별 경로 등록)가 이름검색·주차검색 GET +
 * 체크인 POST에 적용한다. 브랜딩 조회는 대기화면 마운트 시 저빈도 단건 조회라 rate limit
 * 대상에 포함하지 않는다(스팸 위협 낮음 — `PublicIdleContentController`와 동일 판단).
 */
@RestController
@RequestMapping("/public/events/{eid}")
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

    // 브랜딩은 콘솔에서 수시로 변경되므로 대기화면 미디어 서빙의 immutable·max-age=1y 캐시를
    // 그대로 옮기면 색 변경이 최대 1년간 반영되지 않는다 — no-cache로 매 조회 최신 저장값을
    // 반영한다. 키오스크는 대기화면 마운트 시 저빈도 단건 조회라 캐시 비활성화의 성능 부담이 없다.
    @GetMapping("/branding")
    fun getBranding(@PathVariable eid: String): ResponseEntity<ApiResponse<PublicEventBrandingResponse>> =
        ResponseEntity.ok()
            .cacheControl(CacheControl.noCache())
            .body(ApiResponse(kioskUseCase.getBranding(eid).toPublicEventBrandingResponse()))
}
