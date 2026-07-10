package kr.co.morymaker.api.web

import jakarta.validation.Valid
import kr.co.morymaker.api.application.port.`in`.SmsSendCommand
import kr.co.morymaker.api.application.port.`in`.SmsUseCase
import kr.co.morymaker.api.config.PublicProperties
import kr.co.morymaker.api.domain.security.MoryRoles
import kr.co.morymaker.api.dto.ApiResponse
import kr.co.morymaker.api.dto.SmsGateRequest
import kr.co.morymaker.api.dto.SmsGateResponse
import kr.co.morymaker.api.dto.SmsLogResponse
import kr.co.morymaker.api.dto.SmsPreviewRequest
import kr.co.morymaker.api.dto.SmsPreviewResponse
import kr.co.morymaker.api.dto.SmsResendRequest
import kr.co.morymaker.api.dto.SmsSendItemResponse
import kr.co.morymaker.api.dto.SmsSendRequest
import kr.co.morymaker.api.dto.SmsSendResultResponse
import kr.co.morymaker.api.dto.SmsTemplateResponse
import kr.co.morymaker.api.dto.SmsTemplateUpdateRequest
import kr.co.morymaker.api.dto.toResponse
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 문자 도메인 API(§7) — 전 메서드 `EVENT_ADMIN`(관리자 콘솔, 실행자 제외).
 *
 * `{eid}` 경로변수명은 고정 — `EventScopeInterceptor`가 "eid" 키만 검사한다(ParkingZoneController
 * 주석 근거). `props.eventBaseUrl`을 렌더링이 필요한 4개 메서드(preview·gate·send·resend)에
 * 전달한다 — `PublicHubController`가 동일하게 `PublicProperties`를 주입·소비하는 선례와 정합.
 */
@RestController
@RequestMapping("/api/events/{eid}")
class SmsController(
    private val smsUseCase: SmsUseCase,
    private val props: PublicProperties,
) {

    @GetMapping(value = ["/sms-template", "/sms-template/"])
    @PreAuthorize(MoryRoles.HAS_ADMIN_CONSOLE)
    fun getTemplate(@PathVariable eid: String): ApiResponse<SmsTemplateResponse> =
        ApiResponse(smsUseCase.getTemplate(eid).toResponse())

    @PutMapping(value = ["/sms-template", "/sms-template/"])
    @PreAuthorize(MoryRoles.HAS_ADMIN_CONSOLE)
    fun upsertTemplate(
        @PathVariable eid: String,
        @Valid @RequestBody request: SmsTemplateUpdateRequest,
    ): ApiResponse<SmsTemplateResponse> =
        ApiResponse(smsUseCase.upsertTemplate(eid, request.body).toResponse())

    @PostMapping("/sms-template/preview")
    @PreAuthorize(MoryRoles.HAS_ADMIN_CONSOLE)
    fun preview(
        @PathVariable eid: String,
        @Valid @RequestBody request: SmsPreviewRequest,
    ): ApiResponse<SmsPreviewResponse> =
        ApiResponse(smsUseCase.preview(eid, request.guestId, props.eventBaseUrl).toResponse())

    @PostMapping("/sms/send/gate")
    @PreAuthorize(MoryRoles.HAS_ADMIN_CONSOLE)
    fun gate(
        @PathVariable eid: String,
        @RequestBody request: SmsGateRequest,
    ): ApiResponse<SmsGateResponse> =
        ApiResponse(smsUseCase.gate(eid, request.excludeAlreadySent, props.eventBaseUrl).toResponse())

    @PostMapping("/sms/send")
    @PreAuthorize(MoryRoles.HAS_ADMIN_CONSOLE)
    fun send(
        @PathVariable eid: String,
        @RequestBody request: SmsSendRequest,
    ): ApiResponse<SmsSendResultResponse> {
        val command = SmsSendCommand(excludeAlreadySent = request.excludeAlreadySent, confirm = request.confirm)
        return ApiResponse(smsUseCase.send(eid, command, props.eventBaseUrl).toResponse())
    }

    @PostMapping("/sms/resend")
    @PreAuthorize(MoryRoles.HAS_ADMIN_CONSOLE)
    fun resend(
        @PathVariable eid: String,
        @Valid @RequestBody request: SmsResendRequest,
    ): ApiResponse<SmsSendItemResponse> =
        ApiResponse(smsUseCase.resend(eid, request.guestId, request.confirm, props.eventBaseUrl).toResponse())

    @GetMapping(value = ["/sms-log", "/sms-log/"])
    @PreAuthorize(MoryRoles.HAS_ADMIN_CONSOLE)
    fun listLog(@PathVariable eid: String): ApiResponse<List<SmsLogResponse>> =
        ApiResponse(smsUseCase.listLog(eid).map { it.toResponse() })
}
