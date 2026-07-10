package kr.co.morymaker.api.web

import jakarta.validation.Valid
import kr.co.morymaker.api.application.port.`in`.PublicParkingUseCase
import kr.co.morymaker.api.application.port.`in`.RegisterParkingResult
import kr.co.morymaker.api.application.port.`in`.SelfParkCommand
import kr.co.morymaker.api.dto.ApiResponse
import kr.co.morymaker.api.dto.PublicSlotResponse
import kr.co.morymaker.api.dto.RecordRegisterResponse
import kr.co.morymaker.api.dto.SelfParkRequest
import kr.co.morymaker.api.dto.toResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 자리 QR 공개 API(§10-3·§10-4) — 무인증. slotCode 유효성(파싱+구획 존재+범위)만으로 인가를
 * 대체한다. POST는 `PublicRateLimitInterceptor`(`WebMvcConfig`가 등록)가 스팸 방어를 담당한다 —
 * 이 컨트롤러는 `@PreAuthorize`를 두지 않는다.
 */
@RestController
@RequestMapping("/api/public/p/{slotCode}")
class PublicParkingController(
    private val publicParkingUseCase: PublicParkingUseCase,
) {

    @GetMapping(value = ["", "/"])
    fun getSlot(@PathVariable slotCode: String): ApiResponse<PublicSlotResponse> =
        ApiResponse(publicParkingUseCase.getSlotView(slotCode).toResponse())

    /** 셀프 주차 등록(§10-4) — 승계 3분기 결과에 따라 HTTP 상태를 분기한다(`ParkingRecordController`와 동일, PARKED=201 / 그 외=200). */
    @PostMapping("/park")
    fun park(
        @PathVariable slotCode: String,
        @Valid @RequestBody request: SelfParkRequest,
    ): ResponseEntity<ApiResponse<RecordRegisterResponse>> {
        val command = SelfParkCommand(
            plate = request.plate,
            vipName = request.vipName,
            phone = request.phone,
            token = request.token,
        )
        val result = publicParkingUseCase.selfPark(slotCode, command)
        val status = if (result.result == RegisterParkingResult.RESULT_PARKED) HttpStatus.CREATED else HttpStatus.OK
        return ResponseEntity.status(status).body(ApiResponse(result.toResponse()))
    }
}
