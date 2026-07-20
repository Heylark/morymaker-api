package kr.co.morymaker.api.web

import jakarta.validation.Valid
import kr.co.morymaker.api.application.port.`in`.ParkingRecordUseCase
import kr.co.morymaker.api.application.port.`in`.RecordListQuery
import kr.co.morymaker.api.application.port.`in`.RegisterParkingCommand
import kr.co.morymaker.api.application.port.`in`.RegisterParkingResult
import kr.co.morymaker.api.domain.security.MoryRoles
import kr.co.morymaker.api.dto.ApiResponse
import kr.co.morymaker.api.dto.CheckoutResponse
import kr.co.morymaker.api.dto.RecordCreateRequest
import kr.co.morymaker.api.dto.RecordRegisterResponse
import kr.co.morymaker.api.dto.RecordResponse
import kr.co.morymaker.api.dto.ReviewClearResponse
import kr.co.morymaker.api.dto.toResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 주차 기록 API(§6-5~6-8) — 전 메서드 `EVENT_STAFF`/`EVENT_ADMIN`(보드 처리 PRK-02 포함).
 *
 * `{eid}` 경로변수명은 고정 — `EventScopeInterceptor`가 "eid" 키만 검사한다(00-research 발견 3).
 */
@RestController
@RequestMapping("/events/{eid}/parking-records")
class ParkingRecordController(
    private val recordUseCase: ParkingRecordUseCase,
) {

    @GetMapping(value = ["", "/"])
    @PreAuthorize(MoryRoles.HAS_EVENT_ACCESS)
    fun list(
        @PathVariable eid: String,
        @RequestParam(required = false) zoneId: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) plateTail: String?,
        @RequestParam(required = false) reviewNeeded: Boolean?,
    ): ApiResponse<List<RecordResponse>> {
        val query = RecordListQuery(zoneId = zoneId, status = status, plateTail = plateTail, reviewNeeded = reviewNeeded)
        return ApiResponse(recordUseCase.listRecords(eid, query).map { it.toResponse() })
    }

    /** 등록(§6-6) — 승계 3분기 결과에 따라 HTTP 상태를 분기한다(PARKED=201 / 그 외=200). */
    @PostMapping(value = ["", "/"])
    @PreAuthorize(MoryRoles.HAS_EVENT_ACCESS)
    fun register(
        @PathVariable eid: String,
        @Valid @RequestBody request: RecordCreateRequest,
    ): ResponseEntity<ApiResponse<RecordRegisterResponse>> {
        val command = RegisterParkingCommand(
            slotSig = request.slotSig,
            zoneId = request.zoneId,
            plate = request.plate,
            phone = request.phone,
            vipName = request.vipName,
            registeredBy = request.registeredBy,
        )
        val result = recordUseCase.register(eid, command)
        val status = if (result.result == RegisterParkingResult.RESULT_PARKED) HttpStatus.CREATED else HttpStatus.OK
        return ResponseEntity.status(status).body(ApiResponse(result.toResponse()))
    }

    @PostMapping("/{id}/checkout")
    @PreAuthorize(MoryRoles.HAS_EVENT_ACCESS)
    fun checkout(@PathVariable eid: String, @PathVariable id: String): ApiResponse<CheckoutResponse> {
        val record = recordUseCase.checkout(eid, id)
        return ApiResponse(CheckoutResponse(id = record.id, status = record.status))
    }

    @PostMapping("/{id}/review-clear")
    @PreAuthorize(MoryRoles.HAS_EVENT_ACCESS)
    fun clearReview(@PathVariable eid: String, @PathVariable id: String): ApiResponse<ReviewClearResponse> {
        val record = recordUseCase.clearReview(eid, id)
        return ApiResponse(ReviewClearResponse(id = record.id, reviewNeeded = record.reviewNeeded))
    }
}
