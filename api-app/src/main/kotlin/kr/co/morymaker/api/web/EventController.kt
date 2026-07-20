package kr.co.morymaker.api.web

import jakarta.validation.Valid
import kr.co.morymaker.api.application.port.`in`.CreateEventCommand
import kr.co.morymaker.api.application.port.`in`.EventUseCase
import kr.co.morymaker.api.application.port.`in`.UpdateBrandingCommand
import kr.co.morymaker.api.application.port.`in`.UpdateEventCommand
import kr.co.morymaker.api.domain.security.MoryRoles
import kr.co.morymaker.api.dto.ApiResponse
import kr.co.morymaker.api.dto.EventBrandingUpdateRequest
import kr.co.morymaker.api.dto.EventCreateRequest
import kr.co.morymaker.api.dto.EventResponse
import kr.co.morymaker.api.dto.EventUpdateRequest
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
 * 행사(event) API(02-api-spec §2-1~2-4·§11-1) — 조회·생성·수정·브랜딩 저장 게이트.
 *
 * 역할게이트(L1)는 `@PreAuthorize`로 이 클래스에서 직접 선언한다. 행사 스코프게이트(L2)는
 * `{eid}` 경로에 한해 `EventScopeInterceptor`(Layer2a)가 이 컨트롤러 진입 전에 이미 적용했고,
 * `EventService`의 각 메서드(Layer2b)가 한 번 더 재검증한다 — 이 클래스는 스코프 판단을 직접
 * 하지 않는다.
 *
 * `update`(§2-4)와 `updateBranding`(§11-1)은 물리적으로 다른 컬럼셋만 저장한다 —
 * `EventUpdateRequest`엔 컬러 필드 자체가 없어 브랜딩이 이 경로로 새는 것이 구조적으로 불가능하다.
 */
@RestController
@RequestMapping("/events")
class EventController(
    private val eventUseCase: EventUseCase,
) {

    @GetMapping(value = ["", "/"])
    @PreAuthorize(MoryRoles.HAS_ADMIN_CONSOLE)
    fun list(): ApiResponse<List<EventResponse>> =
        ApiResponse(eventUseCase.listEvents().map { it.toResponse() })

    @GetMapping("/{eid}")
    @PreAuthorize(MoryRoles.HAS_ADMIN_CONSOLE)
    fun get(@PathVariable eid: String): ApiResponse<EventResponse> =
        ApiResponse(eventUseCase.getEvent(eid).toResponse())

    @PostMapping(value = ["", "/"])
    @PreAuthorize(MoryRoles.HAS_SYSTEM_ADMIN)
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: EventCreateRequest): ApiResponse<EventResponse> {
        val command = CreateEventCommand(
            name = request.name,
            eventDate = request.eventDate,
            place = request.place,
            type = request.type,
            bgColor = request.bgColor,
            pointColor = request.pointColor,
            titleColor = request.titleColor,
            bodyColor = request.bodyColor,
            kv = request.kv,
        )
        return ApiResponse(eventUseCase.createEvent(command).toResponse())
    }

    @PutMapping("/{eid}")
    @PreAuthorize(MoryRoles.HAS_ADMIN_CONSOLE)
    fun update(
        @PathVariable eid: String,
        @Valid @RequestBody request: EventUpdateRequest,
    ): ApiResponse<EventResponse> {
        val command = UpdateEventCommand(
            name = request.name,
            eventDate = request.eventDate,
            place = request.place,
            type = request.type,
            kv = request.kv,
            status = request.status,
            active = request.active,
        )
        return ApiResponse(eventUseCase.updateEvent(eid, command).toResponse())
    }

    @PutMapping("/{eid}/branding")
    @PreAuthorize(MoryRoles.HAS_ADMIN_CONSOLE)
    fun updateBranding(
        @PathVariable eid: String,
        @Valid @RequestBody request: EventBrandingUpdateRequest,
    ): ApiResponse<EventResponse> {
        val command = UpdateBrandingCommand(
            bgColor = request.bgColor,
            pointColor = request.pointColor,
            titleColor = request.titleColor,
            bodyColor = request.bodyColor,
            kv = request.kv,
            defaultIdleMode = request.defaultIdleMode,
        )
        return ApiResponse(eventUseCase.updateBranding(eid, command).toResponse())
    }
}
