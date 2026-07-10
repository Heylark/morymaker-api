package kr.co.morymaker.api.web

import jakarta.validation.Valid
import kr.co.morymaker.api.application.port.`in`.AssignmentEntry
import kr.co.morymaker.api.application.port.`in`.BulkAssignCommand
import kr.co.morymaker.api.application.port.`in`.SeatAssignmentUseCase
import kr.co.morymaker.api.domain.security.MoryRoles
import kr.co.morymaker.api.dto.ApiResponse
import kr.co.morymaker.api.dto.Meta
import kr.co.morymaker.api.dto.SeatAssignmentBulkUpdateRequest
import kr.co.morymaker.api.dto.SeatSlotResponse
import kr.co.morymaker.api.dto.toResponse
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 좌석 배정 API(§12-4~5, ADM-06 배정 모달) — 전 메서드 `EVENT_ADMIN`(관리자 콘솔, 실행자 제외).
 *
 * `{eid}` 경로변수명은 고정 — `EventScopeInterceptor`가 "eid" 키만 검사한다(00-research 발견 3).
 */
@RestController
@RequestMapping("/api/events/{eid}/seat-assignments")
class SeatAssignmentController(
    private val assignmentUseCase: SeatAssignmentUseCase,
) {

    /** 그룹 배정 조회(§12-4) — 대용량 자유석은 `page`로 조회. */
    @GetMapping(value = ["", "/"])
    @PreAuthorize(MoryRoles.HAS_ADMIN_CONSOLE)
    fun list(
        @PathVariable eid: String,
        @RequestParam groupNo: Int,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): ApiResponse<List<SeatSlotResponse>> {
        val result = assignmentUseCase.listAssignments(eid, groupNo, page, size)
        return ApiResponse(
            data = result.items.map { it.toResponse() },
            meta = Meta(total = result.total, page = page, size = size),
        )
    }

    /** 일괄 배정 변경(§12-5) — 드래그 재배치·번호 이동·빈 좌석 추가 모두 이 원자 교체로 처리한다. */
    @PutMapping(value = ["", "/"])
    @PreAuthorize(MoryRoles.HAS_ADMIN_CONSOLE)
    fun replace(
        @PathVariable eid: String,
        @Valid @RequestBody request: SeatAssignmentBulkUpdateRequest,
    ): ApiResponse<List<SeatSlotResponse>> {
        val command = BulkAssignCommand(
            groupNo = request.groupNo,
            assignments = request.assignments.map { AssignmentEntry(ord = it.ord, guestId = it.guestId) },
        )
        return ApiResponse(assignmentUseCase.replaceAssignments(eid, command).map { it.toResponse() })
    }
}
