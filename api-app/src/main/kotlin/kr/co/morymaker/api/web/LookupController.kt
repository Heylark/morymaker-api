package kr.co.morymaker.api.web

import kr.co.morymaker.api.application.port.`in`.LookupUseCase
import kr.co.morymaker.api.domain.security.MoryRoles
import kr.co.morymaker.api.dto.ApiResponse
import kr.co.morymaker.api.dto.LookupResponse
import kr.co.morymaker.api.dto.Meta
import kr.co.morymaker.api.dto.toResponse
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 실행자 통합조회 API(§9, HLP) — 안내데스크(HLP-01)의 이름·차량번호 한 칸 검색. `EVENT_STAFF`까지
 * 포함해 실행자 웹이 직접 호출한다(명단 CRUD `GuestController`는 관리자 콘솔 전용과 대비).
 *
 * `{eid}` 경로변수명은 고정 — `EventScopeInterceptor`가 "eid" 키만 검사한다(00-research 발견 3).
 */
@RestController
@RequestMapping("/events/{eid}/lookup")
class LookupController(
    private val lookupUseCase: LookupUseCase,
) {

    // q는 반드시 required=false로 선언한다 — 디폴트(required=true)로 두면 파라미터 자체가
    // 누락된 요청이 MissingServletRequestParameterException을 던지는데, 이 예외는
    // GlobalExceptionHandler에 전용 핸들러가 없어 catch-all이 500으로 잘못 응답한다(정답은 400).
    // 널/빈 문자열 검증은 LookupService.lookup의 require() 단일 지점에 위임한다.
    @GetMapping(value = ["", "/"])
    @PreAuthorize(MoryRoles.HAS_EVENT_ACCESS)
    fun lookup(
        @PathVariable eid: String,
        @RequestParam(required = false) q: String?,
    ): ApiResponse<List<LookupResponse>> {
        val result = lookupUseCase.lookup(eid, q ?: "")
        return ApiResponse(
            data = result.items.map { it.toResponse() },
            meta = Meta(total = result.total, searchState = result.searchState),
        )
    }
}
