package kr.co.morymaker.api.dto

import kr.co.morymaker.api.application.port.`in`.ImportConfirmResult

/** 엑셀 병합 확정 응답(§4-6) — 실제 insert/update/취소 결과. */
data class GuestImportResultResponse(
    val newCount: Int,
    val updatedCount: Int,
    val cancelledCount: Int,
    val invalidRows: List<InvalidImportRowResponse>,
    val tokenPreserved: Boolean,
)

fun ImportConfirmResult.toResponse(): GuestImportResultResponse = GuestImportResultResponse(
    newCount = newCount,
    updatedCount = updatedCount,
    cancelledCount = cancelledCount,
    invalidRows = invalidRows.map { it.toResponse() },
    tokenPreserved = tokenPreserved,
)
