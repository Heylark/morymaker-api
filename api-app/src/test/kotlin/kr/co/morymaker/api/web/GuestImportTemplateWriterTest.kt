package kr.co.morymaker.api.web

import org.apache.poi.ss.usermodel.WorkbookFactory
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockMultipartFile
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [GuestImportTemplateWriter] 단위 테스트 — R2(컬럼 계약 이중화 방지)의 실질 가드는
 * round-trip이다. 컬럼 정의가 [GuestImportColumn] 밖으로 갈라지는 순간 이 테스트가 RED가
 * 된다(grep 검증은 ADR-001의 보조 수단일 뿐).
 */
class GuestImportTemplateWriterTest {

    private fun multipart(bytes: ByteArray) =
        MockMultipartFile(
            "file",
            "명단업로드양식.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            bytes,
        )

    @Test
    fun `생성한 템플릿을 파서에 다시 먹이면 예외 없이 예시 2행이 파싱된다`() {
        val bytes = GuestImportTemplateWriter.write()

        val rows = GuestExcelParser.parse(multipart(bytes))

        assertEquals(2, rows.size)
        assertEquals("홍길동(예시)", rows[0].name)
        assertEquals("모리메이커", rows[0].org)
        assertEquals("12가3456", rows[0].plate)
        assertNull(rows[0].seatGroupLabel, "좌석그룹 예시값은 비워둔다(ADR-006 — 현재 저장 미반영 컬럼 유입 방지)")
        assertEquals("김영희(예시)", rows[1].name)
        assertNull(rows[1].plate, "2번째 예시행은 선택 컬럼을 비워 '빈칸이어도 된다'를 보여준다")
    }

    @Test
    fun `템플릿 0행 6칸은 GuestImportColumn 헤더 라벨과 정확히 일치한다`() {
        val bytes = GuestImportTemplateWriter.write()

        WorkbookFactory.create(bytes.inputStream()).use { workbook ->
            val headerRow = workbook.getSheetAt(0).getRow(GuestImportColumn.HEADER_ROW_INDEX)
            val actual = GuestImportColumn.entries.map { headerRow.getCell(it.index).stringCellValue }
            assertEquals(GuestImportColumn.entries.map { it.header }, actual)
        }
    }
}
