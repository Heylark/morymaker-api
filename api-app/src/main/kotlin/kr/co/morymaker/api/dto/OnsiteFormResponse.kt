package kr.co.morymaker.api.dto

import kr.co.morymaker.api.domain.event.Event

/** 현장등록 폼 진입 응답(§10-5) — 폼 렌더용 브랜딩만, 민감정보 0. */
data class OnsiteFormResponse(val event: OnsiteFormEventView)

data class OnsiteFormEventView(val name: String, val bgColor: String?, val pointColor: String?)

fun Event.toOnsiteFormResponse(): OnsiteFormResponse =
    OnsiteFormResponse(OnsiteFormEventView(name = name, bgColor = bgColor, pointColor = pointColor))
