package kr.co.morymaker.api.web

import jakarta.validation.Valid
import kr.co.morymaker.api.application.port.`in`.PublicHubUseCase
import kr.co.morymaker.api.config.PublicProperties
import kr.co.morymaker.api.dto.ApiResponse
import kr.co.morymaker.api.dto.PreregPlateRequest
import kr.co.morymaker.api.dto.PreregPlateResponse
import kr.co.morymaker.api.dto.PublicHubResponse
import kr.co.morymaker.api.dto.toResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 개인 허브 공개 API(§10-1·§10-2) — 무인증. `SecurityConfig`의 `/api/public` 하위 전체 permitAll이
 * 이미 인증을 면제하며, 이 컨트롤러는 `@PreAuthorize`를 두지 않는다(역할 게이트 대상 자체가
 * 아니다). 인가는 [PublicHubUseCase] 구현체의 token capability 유효성 검증으로 대체된다.
 */
@RestController
@RequestMapping("/api/public/u/{token}")
class PublicHubController(
    private val publicHubUseCase: PublicHubUseCase,
    private val publicProperties: PublicProperties,
) {

    @GetMapping(value = ["", "/"])
    fun getHub(@PathVariable token: String): ApiResponse<PublicHubResponse> =
        ApiResponse(publicHubUseCase.getHub(token).toResponse(publicProperties))

    @PostMapping("/prereg-plate")
    fun updatePrereg(
        @PathVariable token: String,
        @Valid @RequestBody request: PreregPlateRequest,
    ): ApiResponse<PreregPlateResponse> {
        val result = publicHubUseCase.updatePrereg(token, request.plate)
        return ApiResponse(
            PreregPlateResponse(
                plate = result.guest.plate ?: request.plate,
                message = "사전 등록 완료 — 현장에서 자리 QR만 스캔하면 즉시 매핑됩니다",
            ),
        )
    }
}
