package kr.co.morymaker.api.dto

import kr.co.morymaker.api.domain.event.Event
import java.time.Instant

/**
 * 행사 응답 DTO(camelCase, 02-api-spec §2). 목록·단건 조회 양쪽에서 동일하게 사용한다
 * (단건만 필요한 `titleColor`/`bodyColor`/`smsPolicy`가 목록 응답에도 포함되지만, 추가 필드는
 * 기존 클라이언트 파싱을 깨지 않는다 — 02-api-spec §2-1/§2-3 두 응답을 단일 DTO로 단순화).
 */
data class EventResponse(
    val id: String,
    val name: String,
    val eventDate: Instant?,
    val place: String?,
    val type: String?,
    val status: String,
    val active: Boolean,
    val bgColor: String?,
    val pointColor: String?,
    val titleColor: String?,
    val bodyColor: String?,
    val kv: String?,
    val smsPolicy: String?,
)

fun Event.toResponse(): EventResponse = EventResponse(
    id = id,
    name = name,
    eventDate = eventDate,
    place = place,
    type = type,
    status = status,
    active = active,
    bgColor = bgColor,
    pointColor = pointColor,
    titleColor = titleColor,
    bodyColor = bodyColor,
    kv = kv,
    smsPolicy = smsPolicy,
)
