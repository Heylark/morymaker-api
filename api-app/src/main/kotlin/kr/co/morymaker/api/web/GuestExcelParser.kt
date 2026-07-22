package kr.co.morymaker.api.web

import kr.co.morymaker.api.application.port.`in`.GuestImportRow
import org.apache.poi.EncryptedDocumentException
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.springframework.web.multipart.MultipartFile
import java.io.IOException

/**
 * 엑셀 업로드(§4-5·4-6) MultipartFile → [GuestImportRow] 파싱 — api-app(컨트롤러) 전용.
 *
 * application 레이어가 Apache POI/multipart 타입에 오염되지 않도록 파싱을 이 계층에서
 * 끝내고, 서비스에는 파싱된 값 목록만 전달한다(02-architect §10 권고).
 *
 * 컬럼 계약은 [GuestImportColumn] 하나만 본다 — 열 순서·헤더 라벨을 이 파일에 다시 적지 않는다.
 * 6열 구성 자체는 실 엑셀 양식 미수령 상태의 잠정 계약이다(import 매칭 키 결정과 동일 전제, 실
 * 양식 수령 시 함께 재검토 대상).
 */
internal object GuestExcelParser {

    fun parse(file: MultipartFile): List<GuestImportRow> {
        openWorkbook(file).use { workbook ->
            val sheet = workbook.getSheetAt(0)

            // 헤더 계약 검증 — 데이터 행을 읽기 전에 끝낸다. 열 위치가 밀린 파일을 그대로 파싱하면
            // 예외 없이 잘못된 칸에 저장되어(이름 자리에 연번, 연락처 자리에 직함) 사후 발견이 어렵다.
            //
            // ⚠️ 정책 결정 지점은 아래 throw 한 곳뿐이다. "불일치를 경고로만 알리고 진행"으로 바꿔야
            //    하면 findHeaderMismatches()는 그대로 두고 이 자리만 교체한다.
            val mismatches = findHeaderMismatches(sheet.getRow(GuestImportColumn.HEADER_ROW_INDEX))
            if (mismatches.isNotEmpty()) throw GuestImportHeaderMismatchException(mismatches)

            val rows = mutableListOf<GuestImportRow>()
            // 헤더 다음 행부터 데이터. rowNumber는 엑셀 화면 행 번호(1-based)와 정합시킨다.
            for (rowIndex in (GuestImportColumn.HEADER_ROW_INDEX + 1)..sheet.lastRowNum) {
                val row = sheet.getRow(rowIndex) ?: continue
                val name = cellText(row, GuestImportColumn.NAME.index)
                val org = cellText(row, GuestImportColumn.ORG.index)
                val title = cellText(row, GuestImportColumn.TITLE.index)
                val phone = cellText(row, GuestImportColumn.PHONE.index)
                val plate = cellText(row, GuestImportColumn.PLATE.index)
                val seatGroupLabel = cellText(row, GuestImportColumn.SEAT_GROUP_LABEL.index)
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

    /**
     * 업로드된 바이트를 워크북으로 여는 단계 — 이 함수의 몸통이 곧 "열지 못한 파일을 사용자 안내로
     * 번역하는" 경계다. 열기에 성공한 뒤의 시트·행·셀 접근은 이미 메모리에 올라온 구조를 다루므로
     * 여기의 catch가 닿지 않고, 닿아서도 안 된다.
     *
     * 스트림을 여는 첫 줄이 catch 밖에 있는 것은 의도적이다. 그 실패는 서버가 자기 임시 파일을 열지
     * 못한 것이라 사용자 파일 탓으로 안내하면 거짓이 되고, 이 변경이 없애려는 오분류(입력 오류를 서버
     * 장애로 표시)의 정확한 반대 방향이 된다.
     *
     * 두 catch는 서로 겹치지 않는 타입이라 순서가 동작에 영향을 주지 않는다. 열 수 없는 이유가 다르면
     * 사용자가 해야 할 일도 다르므로(양식 다시 받기 ↔ 암호 해제) 예외를 하나로 합치지 않는다.
     */
    private fun openWorkbook(file: MultipartFile): Workbook {
        val uploaded = file.inputStream
        return try {
            WorkbookFactory.create(uploaded)
        } catch (e: IOException) {
            throw GuestImportFileUnreadableException(e)
        } catch (e: EncryptedDocumentException) {
            throw GuestImportFilePasswordProtectedException(e)
        }
    }

    /** 계약 6열만 대조한다. 7열 이후 추가 열은 A~F 위치를 밀지 않으므로 무시한다. */
    private fun findHeaderMismatches(headerRow: Row?): List<ImportHeaderMismatch> =
        GuestImportColumn.entries.mapNotNull { column ->
            val actual = headerRow?.let { cellText(it, column.index) }
            if (GuestImportColumn.normalize(actual) == GuestImportColumn.normalize(column.header)) null
            else ImportHeaderMismatch(column.index + 1, column.header, actual)
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
