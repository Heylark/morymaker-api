package kr.co.morymaker.api.web

import jakarta.validation.Valid
import kr.co.morymaker.api.application.port.`in`.OnsiteRegisterCommand
import kr.co.morymaker.api.application.port.`in`.PublicOnsiteUseCase
import kr.co.morymaker.api.config.PublicProperties
import kr.co.morymaker.api.dto.ApiResponse
import kr.co.morymaker.api.dto.OnsiteFormResponse
import kr.co.morymaker.api.dto.OnsiteRegisterRequest
import kr.co.morymaker.api.dto.OnsiteRegisterResponse
import kr.co.morymaker.api.dto.toOnsiteFormResponse
import kr.co.morymaker.api.dto.toOnsiteRegisterResponse
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * 현장등록 공개 API(§10-5·§10-6) — 무인증. eventCode(=event.id, D2)의 존재·상태(D5)만으로
 * 인가를 대체한다. POST는 `PublicRateLimitInterceptor`(D4, `WebMvcConfig`가 등록)가 스팸 방어를
 * 담당한다 — 이 컨트롤러는 `@PreAuthorize`를 두지 않는다.
 */
@RestController
@RequestMapping("/api/public/r/{eventCode}")
class PublicOnsiteController(
    private val publicOnsiteUseCase: PublicOnsiteUseCase,
    private val publicProperties: PublicProperties,
) {

    @GetMapping(value = ["", "/"])
    fun getForm(@PathVariable eventCode: String): ApiResponse<OnsiteFormResponse> =
        ApiResponse(publicOnsiteUseCase.getOnsiteForm(eventCode).toOnsiteFormResponse())

    @PostMapping(value = ["", "/"])
    @ResponseStatus(HttpStatus.CREATED)
    fun register(
        @PathVariable eventCode: String,
        @Valid @RequestBody request: OnsiteRegisterRequest,
    ): ApiResponse<OnsiteRegisterResponse> {
        val command = OnsiteRegisterCommand(
            name = request.name,
            org = request.org,
            phone = request.phone,
            plate = request.plate,
        )
        val guest = publicOnsiteUseCase.registerOnsite(eventCode, command)
        return ApiResponse(guest.toOnsiteRegisterResponse(publicProperties))
    }
}
