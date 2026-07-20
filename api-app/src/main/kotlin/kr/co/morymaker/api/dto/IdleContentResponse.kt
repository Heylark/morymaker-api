package kr.co.morymaker.api.dto

import kr.co.morymaker.api.application.port.`in`.IdleContentView

/** 대기화면 콘텐츠 응답 DTO(camelCase, §11-2~4 공통 — 관리자·키오스크 양쪽 재사용). */
data class IdleContentResponse(
    val id: String,
    val name: String,
    val kind: String,
    val mode: String?,
    val play: String?,
    val fileUrl: String?,
    val sortOrder: Int,
)

/**
 * [IdleContentView.fileUrl](스토리지 키)을 조회 시점에 완성된 절대 URL로 조립한다 — DB에는
 * 스토리지 키만 저장하고 URL을 박제하지 않는다(`SlotResponse.kt` 동형 패턴). 스토리지 키가
 * 없는 행(구 메타 전용)은 null을 유지한다.
 */
fun IdleContentView.toResponse(mediaBaseUrl: String): IdleContentResponse = IdleContentResponse(
    id = id,
    name = name,
    kind = kind,
    mode = mode,
    play = play,
    fileUrl = fileUrl?.let { "$mediaBaseUrl/public/events/$eventId/idle-contents/$id/file" },
    sortOrder = sortOrder,
)
