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

fun IdleContentView.toResponse(): IdleContentResponse = IdleContentResponse(
    id = id,
    name = name,
    kind = kind,
    mode = mode,
    play = play,
    fileUrl = fileUrl,
    sortOrder = sortOrder,
)
