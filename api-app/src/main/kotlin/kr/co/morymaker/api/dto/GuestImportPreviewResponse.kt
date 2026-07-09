package kr.co.morymaker.api.dto

import kr.co.morymaker.api.application.port.`in`.ImportPreviewResult
import kr.co.morymaker.api.application.port.`in`.InvalidImportRow

/** 엑셀 병합 미리보기 응답(§4-5) — DB 미변경 상태의 분류 요약. */
data class GuestImportPreviewResponse(
    val newCount: Int,
    val updatedCount: Int,
    val excludedCount: Int,
    val invalidRows: List<InvalidImportRowResponse>,
)

data class InvalidImportRowResponse(val rowNumber: Int, val reason: String)

fun ImportPreviewResult.toResponse(): GuestImportPreviewResponse = GuestImportPreviewResponse(
    newCount = newCount,
    updatedCount = updatedCount,
    excludedCount = excludedCount,
    invalidRows = invalidRows.map { it.toResponse() },
)

fun InvalidImportRow.toResponse(): InvalidImportRowResponse = InvalidImportRowResponse(rowNumber, reason)
