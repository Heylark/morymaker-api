package kr.co.morymaker.api.web

import kr.co.morymaker.api.application.port.`in`.GuestImportRow
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.springframework.web.multipart.MultipartFile

/**
 * 엑셀 업로드(§4-5·4-6) MultipartFile → [GuestImportRow] 파싱 — api-app(컨트롤러) 전용.
 *
 * application 레이어가 Apache POI/multipart 타입에 오염되지 않도록 파싱을 이 계층에서
 * 끝내고, 서비스에는 파싱된 값 목록만 전달한다(02-architect §10 권고).
 *
 * A-1 기본 컬럼셋(이름·소속·직함·연락처·차량번호·좌석그룹) 순서를 시트 A~F열로 가정한다 —
 * 모리메이커 실 엑셀 양식 미수령 상태의 잠정 가정(ADR-IMPORT-MATCH-KEY와 동일 전제, 실 양식
 * 수령 시 함께 재검토 대상).
 */
internal object GuestExcelParser {

    private const val COL_NAME = 0
    private const val COL_ORG = 1
    private const val COL_TITLE = 2
    private const val COL_PHONE = 3
    private const val COL_PLATE = 4
    private const val COL_SEAT_GROUP_LABEL = 5

    fun parse(file: MultipartFile): List<GuestImportRow> {
        WorkbookFactory.create(file.inputStream).use { workbook ->
            val sheet = workbook.getSheetAt(0)
            val rows = mutableListOf<GuestImportRow>()
            // 0행은 헤더 — 1행부터 데이터. rowNumber는 엑셀 화면 행 번호(1-based)와 정합시킨다.
            for (rowIndex in 1..sheet.lastRowNum) {
                val row = sheet.getRow(rowIndex) ?: continue
                val name = cellText(row, COL_NAME)
                val org = cellText(row, COL_ORG)
                val title = cellText(row, COL_TITLE)
                val phone = cellText(row, COL_PHONE)
                val plate = cellText(row, COL_PLATE)
                val seatGroupLabel = cellText(row, COL_SEAT_GROUP_LABEL)
                if (listOf(name, org, title, phone, plate, seatGroupLabel).all { it == null }) continue
                rows += GuestImportRow(
                    rowNumber = rowIndex + 1,
                    name = name,
                    org = org,
                    title = title,
                    phone = phone,
                    plate = plate,
                    seatGroupLabel = seatGroupLabel,
                )
            }
            return rows
        }
    }

    private fun cellText(row: Row, index: Int): String? {
        val cell: Cell = row.getCell(index) ?: return null
        val raw = when (cell.cellType) {
            CellType.STRING -> cell.stringCellValue
            // 전화번호·차량번호가 엑셀 자동서식으로 숫자 셀이 된 경우 소수점 없는 정수 문자열로 변환.
            CellType.NUMERIC -> {
                val value = cell.numericCellValue
                if (value == Math.floor(value)) value.toLong().toString() else value.toString()
            }
            CellType.BLANK -> null
            else -> cell.toString()
        }
        return raw?.trim()?.takeIf { it.isNotBlank() }
    }
}
