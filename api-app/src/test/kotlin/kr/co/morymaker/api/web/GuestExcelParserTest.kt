package kr.co.morymaker.api.web

import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockMultipartFile
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [GuestExcelParser] 헤더 검증(§4-5, V1~V5) 단위 테스트 — Spring 컨텍스트 없이 POI 워크북을
 * 메모리에서 직접 만들어 검증한다(`IdleContentControllerTest`의 MockMultipartFile 선례와 동일
 * 발상이나, 이 파일은 HTTP 계층 없이 파서만 단독 호출한다).
 *
 * 실 DB 매칭·부분 실패 롤백은 `GuestImportIntegrationTest`(서비스 계층), cross-tenant·역할
 * 게이트·응답 계약(400 IMPORT_HEADER_MISMATCH)은 `GuestControllerTest`가 각각 검증한다 — 이
 * 파일은 헤더 대조 규칙(V1~V5) 자체에만 집중한다. R2(컬럼 계약 이중화 방지) round-trip은
 * `GuestImportTemplateWriterTest`.
 */
class GuestExcelParserTest {

    private fun workbookBytes(build: (Sheet) -> Unit): ByteArray {
        XSSFWorkbook().use { wb ->
            val sheet = wb.createSheet("시트")
            build(sheet)
            ByteArrayOutputStream().use { bos ->
                wb.write(bos)
                return bos.toByteArray()
            }
        }
    }

    private fun multipart(bytes: ByteArray) =
        MockMultipartFile(
            "file",
            "명단.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            bytes,
        )

    private fun fillRow(row: Row, vararg values: String) {
        values.forEachIndexed { index, value -> row.createCell(index).setCellValue(value) }
    }

    private val standardHeaders = arrayOf("이름", "소속", "직함", "연락처", "차량번호", "좌석그룹")

    @Test
    fun `정규 헤더 + 2행은 기존과 동일한 GuestImportRow 2건으로 파싱된다`() {
        val bytes = workbookBytes { sheet ->
            fillRow(sheet.createRow(0), *standardHeaders)
            fillRow(sheet.createRow(1), "김진우", "모리메이커", "대표이사", "010-1234-5678", "12가3456", "A열")
            fillRow(sheet.createRow(2), "박서연", "모리테크", "팀장", "010-2345-6789", "34나5678", "B열")
        }

        val rows = GuestExcelParser.parse(multipart(bytes))

        assertEquals(2, rows.size)
        assertEquals("김진우", rows[0].name)
        assertEquals("모리메이커", rows[0].org)
        assertEquals("A열", rows[0].seatGroupLabel)
        assertEquals(2, rows[0].rowNumber)
        assertEquals("박서연", rows[1].name)
        assertEquals(3, rows[1].rowNumber)
    }

    @Test
    fun `맨 앞에 연번 열이 끼어들면 헤더 불일치 예외를 던지고 행을 파싱하지 않는다`() {
        // 00-research 발견 2 재현 — 연번 열 1개가 앞에 끼어들면 계약 6열이 모두 한 칸씩 밀린다.
        val bytes = workbookBytes { sheet ->
            fillRow(sheet.createRow(0), "No.", "이름", "소속", "직함", "연락처", "차량번호")
            fillRow(sheet.createRow(1), "1", "김진우", "모리메이커", "대표이사", "010-1234-5678", "12가3456")
        }

        val exception = assertFailsWith<GuestImportHeaderMismatchException> {
            GuestExcelParser.parse(multipart(bytes))
        }
        assertTrue(exception.mismatches.isNotEmpty(), "밀린 6열 전부 불일치로 잡혀야 한다")
    }

    @Test
    fun `헤더 없이 데이터부터 시작하면 헤더 불일치 예외를 던진다`() {
        val bytes = workbookBytes { sheet ->
            fillRow(sheet.createRow(0), "김진우", "모리메이커", "대표이사", "010-1234-5678", "12가3456", "A열")
        }

        assertFailsWith<GuestImportHeaderMismatchException> { GuestExcelParser.parse(multipart(bytes)) }
    }

    @Test
    fun `헤더 행 자체가 없는 빈 시트도 헤더 불일치 예외를 던진다`() {
        val bytes = workbookBytes { /* 행 0개 — sheet.getRow(0) == null */ }

        assertFailsWith<GuestImportHeaderMismatchException> { GuestExcelParser.parse(multipart(bytes)) }
    }

    @Test
    fun `헤더 라벨의 공백 차이는 관용한다`() {
        val bytes = workbookBytes { sheet ->
            fillRow(sheet.createRow(0), "이름", "소속", "직함", "연락처", "차량 번호", "좌석그룹")
            fillRow(sheet.createRow(1), "김진우", "모리메이커", "대표이사", "010-1234-5678", "12가3456", "A열")
        }

        val rows = GuestExcelParser.parse(multipart(bytes))

        assertEquals(1, rows.size)
        assertEquals("12가3456", rows[0].plate)
    }

    @Test
    fun `6열 뒤 추가 열은 무시되고 정상 파싱된다`() {
        val bytes = workbookBytes { sheet ->
            fillRow(sheet.createRow(0), "이름", "소속", "직함", "연락처", "차량번호", "좌석그룹", "비고")
            fillRow(sheet.createRow(1), "김진우", "모리메이커", "대표이사", "010-1234-5678", "12가3456", "A열", "무관한값")
        }

        val rows = GuestExcelParser.parse(multipart(bytes))

        assertEquals(1, rows.size)
        assertEquals("김진우", rows[0].name)
    }

    @Test
    fun `필수값(이름)이 없는 행도 헤더가 맞으면 파싱은 통과하고 빈 셀은 null이다`() {
        // 이름 누락 자체의 invalidRows 처리는 서비스 계층(GuestImportIntegrationTest) 책임 —
        // 파서는 헤더 계약만 본다.
        val bytes = workbookBytes { sheet ->
            fillRow(sheet.createRow(0), *standardHeaders)
            val row = sheet.createRow(1)
            row.createCell(1).setCellValue("모리메이커")
        }

        val rows = GuestExcelParser.parse(multipart(bytes))

        assertEquals(1, rows.size)
        assertNull(rows[0].name)
        assertEquals("모리메이커", rows[0].org)
    }
}
