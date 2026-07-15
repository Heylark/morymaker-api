package kr.co.morymaker.api.web

import jakarta.validation.Valid
import kr.co.morymaker.api.application.port.`in`.CheckinTarget
import kr.co.morymaker.api.application.port.`in`.CheckinUseCase
import kr.co.morymaker.api.domain.security.MoryRoles
import kr.co.morymaker.api.dto.ApiResponse
import kr.co.morymaker.api.dto.CheckinCancelRequest
import kr.co.morymaker.api.dto.CheckinRequest
import kr.co.morymaker.api.dto.CheckinResponse
import kr.co.morymaker.api.dto.GuestResponse
import kr.co.morymaker.api.dto.toResponse
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 체크인 API(§5) — **SCN 경로만**(인증된 실행자/STAFF·ADMIN). KIO(무인 키오스크) 경로는
 * D2 결정(CP-2 트리거 ⑥, 사용자 결정 위임) 후 별도 태스크로 추가한다(02-architect §8).
 *
 * `{eid}` 경로변수명은 고정 — `EventScopeInterceptor`가 "eid" 키만 검사한다(00-research 발견 3).
 */
@RestController
@RequestMapping("/events/{eid}/checkin")
class CheckinController(
    private val checkinUseCase: CheckinUseCase,
) {

    @PostMapping(value = ["", "/"])
    @PreAuthorize(MoryRoles.HAS_EVENT_ACCESS)
    fun checkin(
        @PathVariable eid: String,
        @RequestBody request: CheckinRequest,
    ): ApiResponse<CheckinResponse> {
        val target = when {
            !request.token.isNullOrBlank() -> CheckinTarget.ByToken(request.token)
            !request.guestId.isNullOrBlank() -> CheckinTarget.ByGuestId(request.guestId)
            else -> throw IllegalArgumentException("token 또는 guestId 중 하나는 필수입니다")
        }
        return ApiResponse(checkinUseCase.checkin(eid, target).toResponse())
    }

    @GetMapping("/scan/{token}")
    @PreAuthorize(MoryRoles.HAS_EVENT_ACCESS)
    fun scanPreview(
        @PathVariable eid: String,
        @PathVariable token: String,
    ): ApiResponse<GuestResponse> = ApiResponse(checkinUseCase.scanPreview(eid, token).toResponse())

    @PostMapping("/cancel")
    @PreAuthorize(MoryRoles.HAS_ADMIN_CONSOLE)
    fun cancelCheckin(
        @PathVariable eid: String,
        @Valid @RequestBody request: CheckinCancelRequest,
    ): ApiResponse<GuestResponse> = ApiResponse(checkinUseCase.cancelCheckin(eid, request.guestId).toResponse())
}
