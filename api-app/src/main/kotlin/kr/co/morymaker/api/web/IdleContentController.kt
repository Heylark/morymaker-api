package kr.co.morymaker.api.web

import jakarta.validation.Valid
import kr.co.morymaker.api.application.port.`in`.IdleContentCreateCommand
import kr.co.morymaker.api.application.port.`in`.IdleContentUpdateCommand
import kr.co.morymaker.api.application.port.`in`.IdleContentUseCase
import kr.co.morymaker.api.domain.security.MoryRoles
import kr.co.morymaker.api.dto.ApiResponse
import kr.co.morymaker.api.dto.IdleContentCreateRequest
import kr.co.morymaker.api.dto.IdleContentResponse
import kr.co.morymaker.api.dto.IdleContentUpdateRequest
import kr.co.morymaker.api.dto.toResponse
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * 대기화면 콘텐츠 관리자 API(§11-2~4) — 전 메서드 `EVENT_ADMIN`(관리자 콘솔, 실행자 제외).
 *
 * `{eid}` 경로변수명은 고정 — `EventScopeInterceptor`가 "eid" 키만 검사한다(00-research 발견 3,
 * ParkingZoneController와 동일 원칙). 키오스크 무인증 조회는 `PublicIdleContentController`
 * (`/public/events/{eid}/idle-contents`)가 별도로 담당한다.
 */
@RestController
@RequestMapping("/events/{eid}/idle-contents")
class IdleContentController(
    private val idleContentUseCase: IdleContentUseCase,
) {

    @GetMapping(value = ["", "/"])
    @PreAuthorize(MoryRoles.HAS_ADMIN_CONSOLE)
    fun list(@PathVariable eid: String): ApiResponse<List<IdleContentResponse>> =
        ApiResponse(idleContentUseCase.list(eid).map { it.toResponse() })

    @PostMapping(value = ["", "/"])
    @PreAuthorize(MoryRoles.HAS_ADMIN_CONSOLE)
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @PathVariable eid: String,
        @Valid @RequestBody request: IdleContentCreateRequest,
    ): ApiResponse<IdleContentResponse> {
        val command = IdleContentCreateCommand(
            name = request.name,
            kind = request.kind,
            mode = request.mode,
            play = request.play,
            sortOrder = request.sortOrder,
        )
        return ApiResponse(idleContentUseCase.create(eid, command).toResponse())
    }

    @PutMapping("/{cid}")
    @PreAuthorize(MoryRoles.HAS_ADMIN_CONSOLE)
    fun update(
        @PathVariable eid: String,
        @PathVariable cid: String,
        @Valid @RequestBody request: IdleContentUpdateRequest,
    ): ApiResponse<IdleContentResponse> {
        val command = IdleContentUpdateCommand(
            mode = request.mode,
            play = request.play,
            sortOrder = request.sortOrder,
        )
        return ApiResponse(idleContentUseCase.update(eid, cid, command).toResponse())
    }
}
