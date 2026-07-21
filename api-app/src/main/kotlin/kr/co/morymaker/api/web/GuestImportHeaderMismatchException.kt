package kr.co.morymaker.api.web

/** 어긋난 머리글 칸 1개. columnNumber는 엑셀 화면 열 번호(1-based)와 정합시킨다. */
data class ImportHeaderMismatch(val columnNumber: Int, val expected: String, val actual: String?)

/**
 * 업로드 엑셀 0행 머리글이 업로드 양식과 다름(§4-5) — [GuestExcelParser.parse]가 던지고
 * [GlobalExceptionHandler]가 400(IMPORT_HEADER_MISMATCH)으로 변환한다.
 *
 * IllegalArgumentException을 상속하지 않는다 — 상속하면 기존 공통 400 핸들러가 먼저 잡아 전용 코드가
 * 사라지고, 화면은 헤더 안내 대신 일반 오류만 보여준다.
 */
class GuestImportHeaderMismatchException(
    val mismatches: List<ImportHeaderMismatch>,
) : RuntimeException(describe(mismatches))

private fun describe(mismatches: List<ImportHeaderMismatch>): String {
    val head = mismatches.take(2).joinToString(", ") {
        "${it.columnNumber}번째 칸은 '${it.expected}'이어야 하는데 '${it.actual ?: "(빈칸)"}'입니다"
    }
    val rest = if (mismatches.size > 2) " 외 ${mismatches.size - 2}칸" else ""
    return "엑셀 첫 행 머리글이 업로드 양식과 다릅니다 — $head$rest. " +
        "상단 [템플릿 받기]로 양식을 내려받아 그대로 채워 주세요."
}
