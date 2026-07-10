package kr.co.morymaker.api.web

import jakarta.validation.Valid
import kr.co.morymaker.api.application.port.`in`.SeatGroupCreateCommand
import kr.co.morymaker.api.application.port.`in`.SeatGroupUpdateCommand
import kr.co.morymaker.api.application.port.`in`.SeatGroupUseCase
import kr.co.morymaker.api.domain.security.MoryRoles
import kr.co.morymaker.api.dto.ApiResponse
import kr.co.morymaker.api.dto.SeatGroupCreateRequest
import kr.co.morymaker.api.dto.SeatGroupDeleteResponse
import kr.co.morymaker.api.dto.SeatGroupResponse
import kr.co.morymaker.api.dto.SeatGroupUpdateRequest
import kr.co.morymaker.api.dto.toResponse
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * 좌석 그룹 API(§12-1~3, ADM-06) — 전 메서드 `EVENT_ADMIN`(관리자 콘솔, 실행자 제외).
 *
 * `{eid}` 경로변수명은 고정 — `EventScopeInterceptor`가 "eid" 키만 검사한다(00-research 발견 3).
 */
@RestController
@RequestMapping("/api/events/{eid}/seat-groups")
class SeatGroupController(
    private val groupUseCase: SeatGroupUseCase,
) {

    @GetMapping(value = ["", "/"])
    @PreAuthorize(MoryRoles.HAS_ADMIN_CONSOLE)
    fun list(@PathVariable eid: String): ApiResponse<List<SeatGroupResponse>> =
        ApiResponse(groupUseCase.listGroups(eid).map { it.toResponse() })

    @PostMapping(value = ["", "/"])
    @PreAuthorize(MoryRoles.HAS_ADMIN_CONSOLE)
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @PathVariable eid: String,
        @Valid @RequestBody request: SeatGroupCreateRequest,
    ): ApiResponse<SeatGroupResponse> {
        val command = SeatGroupCreateCommand(label = request.label, numbering = request.numbering)
        return ApiResponse(groupUseCase.createGroup(eid, command).toResponse())
    }

    @PutMapping("/{gid}")
    @PreAuthorize(MoryRoles.HAS_ADMIN_CONSOLE)
    fun update(
        @PathVariable eid: String,
        @PathVariable gid: String,
        @Valid @RequestBody request: SeatGroupUpdateRequest,
    ): ApiResponse<SeatGroupResponse> {
        val command = SeatGroupUpdateCommand(label = request.label, numbering = request.numbering)
        return ApiResponse(groupUseCase.updateGroup(eid, gid, command).toResponse())
    }

    @DeleteMapping("/{gid}")
    @PreAuthorize(MoryRoles.HAS_ADMIN_CONSOLE)
    fun delete(@PathVariable eid: String, @PathVariable gid: String): ApiResponse<SeatGroupDeleteResponse> {
        groupUseCase.deleteGroup(eid, gid)
        return ApiResponse(SeatGroupDeleteResponse(gid))
    }
}
