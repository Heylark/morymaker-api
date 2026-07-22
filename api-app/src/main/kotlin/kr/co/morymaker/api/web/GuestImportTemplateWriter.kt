package kr.co.morymaker.api.web

import org.apache.poi.ss.usermodel.CellStyle
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayOutputStream

/**
 * 명단 업로드 양식(§4-5) 생성 — [GuestImportColumn] 선언 순서를 그대로 헤더로 쓴다(파서가 읽는
 * 순서와 같은 소스, `StatsExcelWriter` 형태 계승 — POI는 web 레이어에만 배선). 인자 **없음** —
 * 행사별 데이터가 들어가지 않는다는 사실을 시그니처로 박제한다(좌석그룹 실값 안내 시트 등 향후
 * 행사 데이터가 필요해지면 그때 인자를 추가한다).
 *
 * POI 사용 범위는 CellStyle 2종(헤더 bold / 예시 회색 이탤릭)·Font 2종·컬럼 폭 지정으로 한정한다 —
 * 배경 채우기·병합·데이터 유효성(드롭다운)·수식·시트 보호는 쓰지 않는다(과잉 방지).
 */
internal object GuestImportTemplateWriter {

    // A~F(계약 6열) 밖 — 파서·헤더 검증 모두 6열까지만 보므로 이 열은 무해하다.
    private const val NOTE_COLUMN_INDEX = 7
    private const val NOTE_TEXT = "※ 아래 회색 예시 2행을 지우고 실제 명단을 입력해 주세요"

    /**
     * 예시 2행 — 1행은 전 컬럼을 채우고 2행은 선택 컬럼(차량번호)을 비워 "빈칸이어도 된다"를
     * 보여준다. 좌석그룹은 두 행 모두 비운다 — 현재 업로드로는 좌석이 배정되지 않아(§9) 예시값이
     * 있으면 "적었는데 왜 반영이 안 되나"를 적극적으로 유발한다.
     */
    private val exampleRows: List<List<String?>> = listOf(
        listOf("홍길동(예시)", "모리메이커", "대표이사", "010-1234-5678", "12가3456", null),
        listOf("김영희(예시)", "모리테크", "팀장", "010-2345-6789", null, null),
    )

    fun write(): ByteArray {
        XSSFWorkbook().use { wb ->
            val sheet = wb.createSheet("참석자 명단")
            writeHeader(sheet, headerStyle(wb))
            val exampleCellStyle = exampleStyle(wb)
            exampleRows.forEachIndexed { offset, values ->
                writeDataRow(sheet, GuestImportColumn.HEADER_ROW_INDEX + 1 + offset, values, exampleCellStyle)
            }
            GuestImportColumn.entries.forEach { sheet.setColumnWidth(it.index, 4000) }

            ByteArrayOutputStream().use { bos ->
                wb.write(bos)
                return bos.toByteArray()
            }
        }
    }

    private fun writeHeader(sheet: Sheet, style: CellStyle) {
        val row = sheet.createRow(GuestImportColumn.HEADER_ROW_INDEX)
        GuestImportColumn.entries.forEach { column ->
            row.createCell(column.index).apply {
                setCellValue(column.header)
                cellStyle = style
            }
        }
        row.createCell(NOTE_COLUMN_INDEX).setCellValue(NOTE_TEXT)
    }

    private fun writeDataRow(sheet: Sheet, rowIndex: Int, values: List<String?>, style: CellStyle): Row {
        val row = sheet.createRow(rowIndex)
        GuestImportColumn.entries.forEach { column ->
            val cell = row.createCell(column.index)
            cell.cellStyle = style
            values[column.index]?.let { cell.setCellValue(it) }
        }
        return row
    }

    private fun headerStyle(wb: XSSFWorkbook): CellStyle = wb.createCellStyle().apply {
        setFont(wb.createFont().apply { bold = true })
    }

    private fun exampleStyle(wb: XSSFWorkbook): CellStyle = wb.createCellStyle().apply {
        setFont(
            wb.createFont().apply {
                italic = true
                color = IndexedColors.GREY_50_PERCENT.index
            },
        )
    }
}
