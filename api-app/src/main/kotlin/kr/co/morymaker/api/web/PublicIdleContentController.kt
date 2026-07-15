package kr.co.morymaker.api.web

import kr.co.morymaker.api.application.port.`in`.PublicIdleContentUseCase
import kr.co.morymaker.api.dto.ApiResponse
import kr.co.morymaker.api.dto.IdleContentResponse
import kr.co.morymaker.api.dto.toResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 대기화면 콘텐츠 키오스크 공개 조회 API(§11-2, M3) — 무인증. `SecurityConfig`의
 * `/public` 하위 전체 permitAll 규칙이 인증을 면제하며, 이 컨트롤러는 `@PreAuthorize`를
 * 두지 않는다(역할 게이트 대상 자체가 아니다 — `PublicOnsiteController`·`PublicHubController`와
 * 동일 원칙).
 *
 * `/public/events` 하위 경로는 `WebMvcConfig`가 `EventScopeInterceptor`를 등록한
 * `/events` 하위 패턴 밖이라(prefix 상이) 스코프 게이트도 적용되지 않는다 — 의도된 설계다.
 * 격리는 `PublicIdleContentUseCase.listForKiosk`가 위임하는 `event_id` WHERE 필터로만 제공한다.
 *
 * 존재하지 않는 eid도 404가 아니라 빈 배열(200, fail-open)로 응답한다 — 무인증 디스플레이
 * 기기에 오류 처리를 요구하지 않기 위함이며, UUID eid + 기존 공개 폼이 이미 event 존재를
 * 노출하므로 enumeration 신규 노출도 없다.
 */
@RestController
@RequestMapping("/public/events/{eid}/idle-contents")
class PublicIdleContentController(
    private val publicIdleContentUseCase: PublicIdleContentUseCase,
) {

    @GetMapping(value = ["", "/"])
    fun listForKiosk(@PathVariable eid: String): ApiResponse<List<IdleContentResponse>> =
        ApiResponse(publicIdleContentUseCase.listForKiosk(eid).map { it.toResponse() })
}
