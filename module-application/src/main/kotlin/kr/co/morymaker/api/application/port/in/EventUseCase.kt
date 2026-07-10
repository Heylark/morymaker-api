package kr.co.morymaker.api.application.port.`in`

import kr.co.morymaker.api.domain.event.Event
import java.time.Instant

/**
 * 행사 유스케이스 포트-in — api-app의 `EventController`가 호출한다.
 */
interface EventUseCase {

    /** 목록 조회 — 호출자 스코프에 따라 결과가 필터링된다(SYSTEM_ADMIN 전체 / 그 외 담당 행사만). */
    fun listEvents(): List<Event>

    /** 단건 조회 — 스코프 위반 시 [kr.co.morymaker.api.application.security.EventAccessDeniedException]. */
    fun getEvent(eid: String): Event

    /** 행사 생성 — 호출은 `HAS_SYSTEM_ADMIN` 역할 게이트를 통과한 요청만 도달한다(컨트롤러 `@PreAuthorize`). */
    fun createEvent(command: CreateEventCommand): Event

    /**
     * 행사 정보·상태 수정(§2-4). 브랜딩 컬러 4종·defaultIdleMode는 이 경로로 저장되지 않는다
     * ([updateBranding] 전용 — ADM-04 명시 저장 게이트 우회 방지).
     */
    fun updateEvent(eid: String, command: UpdateEventCommand): Event

    /** 브랜딩 명시 저장 게이트(§11-1). 컬러4종+kv+defaultIdleMode만 갱신한다. */
    fun updateBranding(eid: String, command: UpdateBrandingCommand): Event
}

/** [EventUseCase.createEvent] 입력 — `name`만 필수, 나머지는 미지정 시 브랜드 기본값 상속(02-api-spec §2-2). */
data class CreateEventCommand(
    val name: String,
    val eventDate: java.time.Instant?,
    val place: String?,
    val type: String?,
    val bgColor: String?,
    val pointColor: String?,
    val titleColor: String?,
    val bodyColor: String?,
    val kv: String?,
)

/** [EventUseCase.updateEvent] 입력(§2-4). 컬러 필드가 없다 — 저장 게이트. */
data class UpdateEventCommand(
    val name: String,
    val eventDate: Instant?,
    val place: String?,
    val type: String?,
    val kv: String?,
    val status: String,
    val active: Boolean,
)

/** [EventUseCase.updateBranding] 입력(§11-1). */
data class UpdateBrandingCommand(
    val bgColor: String?,
    val pointColor: String?,
    val titleColor: String?,
    val bodyColor: String?,
    val kv: String?,
    val defaultIdleMode: String?,
)
