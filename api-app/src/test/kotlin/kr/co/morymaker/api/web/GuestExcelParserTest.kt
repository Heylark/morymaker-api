package kr.co.morymaker.api.web

import org.apache.poi.EncryptedDocumentException
import org.apache.poi.openxml4j.opc.OPCPackage
import org.apache.poi.poifs.crypt.EncryptionInfo
import org.apache.poi.poifs.crypt.EncryptionMode
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockMultipartFile
import org.springframework.web.multipart.MultipartFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [GuestExcelParser] 헤더 검증(§4-5) 단위 테스트 — Spring 컨텍스트 없이 POI 워크북을
 * 메모리에서 직접 만들어 검증한다(`IdleContentControllerTest`의 MockMultipartFile 선례와 동일
 * 발상이나, 이 파일은 HTTP 계층 없이 파서만 단독 호출한다).
 *
 * 실 DB 매칭·부분 실패 롤백은 `GuestImportIntegrationTest`(서비스 계층), cross-tenant·역할
 * 게이트·응답 계약(400 IMPORT_HEADER_MISMATCH)은 `GuestControllerTest`가 각각 검증한다 — 이
 * 파일은 헤더 대조 규칙 자체(정상 통과·연번 열 삽입 차단·빈 시트 차단·공백 관용·후행 열 무시)에만
 * 집중한다. 생성한 양식을 파서에 되먹여 컬럼 계약이 갈라지지 않았음을 보는 round-trip은
 * `GuestImportTemplateWriterTest`.
 *
 * 기대 헤더 라벨을 [GuestImportColumn]에서 읽지 않고 아래 `standardHeaders`에 그대로 적어 둔 것은
 * 의도적이다 — 프로덕션 정의를 그대로 참조하면 라벨이 바뀌어도 항상 통과하는 동어반복이 된다.
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

    /**
     * 열기 암호가 걸린 워크북을 메모리에서 직접 만든다 — 신규 의존성 없이 현재 `api-app` 의존성
     * (`poi-ooxml`)만으로 성립함을 실행으로 확인한 레시피다(agile 암호화 모드).
     */
    private fun passwordProtectedBytes(): ByteArray {
        val plain = workbookBytes { sheet -> fillRow(sheet.createRow(0), *standardHeaders) }
        POIFSFileSystem().use { fs ->
            val info = EncryptionInfo(EncryptionMode.agile)
            val encryptor = info.encryptor
            encryptor.confirmPassword("morymaker")
            OPCPackage.open(ByteArrayInputStream(plain)).use { opc ->
                encryptor.getDataStream(fs).use { out -> opc.save(out) }
            }
            ByteArrayOutputStream().use { bos ->
                fs.writeFilesystem(bos)
                return bos.toByteArray()
            }
        }
    }

    /**
     * 업로드 스트림 자체를 열 수 없는 상황을 흉내내는 스텁 — 서버가 자기 임시 파일을 읽지 못한
     * 경우를 재현한다. 이 실패는 파일 내용과 무관하므로 번역되지 않고 그대로 전파돼야 한다.
     */
    private fun unopenableFile() = object : MultipartFile {
        override fun getName() = "file"
        override fun getOriginalFilename() = "명단.xlsx"
        override fun getContentType() = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        override fun isEmpty() = false
        override fun getSize() = 1L
        override fun getBytes(): ByteArray = throw IOException("임시 파일을 읽을 수 없습니다")
        override fun getInputStream(): InputStream = throw IOException("임시 파일을 열 수 없습니다")
        override fun transferTo(dest: java.io.File) = throw UnsupportedOperationException()
    }

    /**
     * 업로드 스트림이 실제로 닫히는지 세는 스텁 — 파서에 넘긴 뒤 close 호출 횟수를 읽는다.
     *
     * mark를 지원하지 않게 만든 것은 의도적이다. 운영은 업로드를 디스크로 스풀하도록 설정돼 있어
     * 파일 스트림이 오고 그 스트림은 mark를 지원하지 않는데, 라이브러리는 지원 여부에 따라 서로 다른
     * 내부 경로를 탄다. 관행대로 메모리 스트림을 그대로 쓰면 운영이 실제로는 지나가지 않는 길만
     * 검증하게 된다.
     */
    private class CloseCountingMultipartFile(private val bytes: ByteArray) : MultipartFile {

        var closeCount: Int = 0
            private set

        private val stream: InputStream =
            object : FilterInputStream(ByteArrayInputStream(bytes)) {
                override fun markSupported(): Boolean = false

                override fun close() {
                    closeCount++
                    super.close()
                }
            }

        override fun getName(): String = "file"
        override fun getOriginalFilename(): String = "명단.xlsx"
        override fun getContentType(): String = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        override fun isEmpty(): Boolean = bytes.isEmpty()
        override fun getSize(): Long = bytes.size.toLong()
        override fun getBytes(): ByteArray = bytes

        // 파서는 스트림을 한 번만 요청한다. 같은 인스턴스를 돌려줘 호출 횟수가 흩어지지 않게 한다.
        override fun getInputStream(): InputStream = stream

        override fun transferTo(dest: java.io.File) = throw UnsupportedOperationException()
    }

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

    // ── 열지 못한 파일 번역(§4-5·§5) ─────────────────────────────

    @Test
    fun `엑셀이 아닌 바이트를 올리면 GuestImportFileUnreadableException으로 번역되고 원인은 IOException이다`() {
        val bytes = "이것은 엑셀 파일이 아닙니다".toByteArray()

        val exception = assertFailsWith<GuestImportFileUnreadableException> {
            GuestExcelParser.parse(multipart(bytes))
        }
        assertTrue(exception.cause is IOException)
    }

    @Test
    fun `암호가 걸린 워크북을 올리면 GuestImportFilePasswordProtectedException으로 번역되고 원인은 EncryptedDocumentException이다`() {
        val exception = assertFailsWith<GuestImportFilePasswordProtectedException> {
            GuestExcelParser.parse(multipart(passwordProtectedBytes()))
        }
        assertTrue(exception.cause is EncryptedDocumentException)
    }

    @Test
    fun `손상 안내와 암호 안내는 문구를 공유하지 않는다`() {
        val unreadable = assertFailsWith<GuestImportFileUnreadableException> {
            GuestExcelParser.parse(multipart("이것은 엑셀 파일이 아닙니다".toByteArray()))
        }
        val passwordProtected = assertFailsWith<GuestImportFilePasswordProtectedException> {
            GuestExcelParser.parse(multipart(passwordProtectedBytes()))
        }

        assertTrue(unreadable.message!!.contains("템플릿"), "손상 안내에는 템플릿 유도가 있어야 한다")
        assertFalse(passwordProtected.message!!.contains("템플릿"), "암호 안내에는 템플릿 유도가 없어야 한다")
        assertTrue(unreadable.message != passwordProtected.message, "두 안내 문구는 서로 달라야 한다")
    }

    @Test
    fun `사용자 메시지에는 원인 예외 문구가 노출되지 않는다`() {
        // 라이브러리 영문 진단 문구가 버전에 따라 바뀌더라도(§3-1 실측) cause.message 자체를
        // 기준으로 검사하므로 동어반복이 되지 않는다.
        val unreadable = assertFailsWith<GuestImportFileUnreadableException> {
            GuestExcelParser.parse(multipart("이것은 엑셀 파일이 아닙니다".toByteArray()))
        }
        val passwordProtected = assertFailsWith<GuestImportFilePasswordProtectedException> {
            GuestExcelParser.parse(multipart(passwordProtectedBytes()))
        }

        val unreadableCauseMessage = requireNotNull(unreadable.cause?.message) { "원인 예외 문구가 실측 대상이어야 한다" }
        val passwordProtectedCauseMessage =
            requireNotNull(passwordProtected.cause?.message) { "원인 예외 문구가 실측 대상이어야 한다" }
        assertFalse(unreadable.message!!.contains(unreadableCauseMessage))
        assertFalse(passwordProtected.message!!.contains(passwordProtectedCauseMessage))
    }

    @Test
    fun `0바이트 파일은 기존과 동일하게 IllegalArgumentException 계열로 남는다`() {
        // 신규 번역(IOException·EncryptedDocumentException)이 이 경로를 가로채지 않아야 한다.
        assertFailsWith<IllegalArgumentException> {
            GuestExcelParser.parse(multipart(ByteArray(0)))
        }
    }

    @Test
    fun `업로드 스트림 획득 자체가 실패하면 번역되지 않고 IOException이 그대로 전파된다`() {
        // 경계 고정 — file.inputStream 실패는 서버 사정이라 사용자 파일 탓으로 번역하면 안 된다.
        assertFailsWith<IOException> {
            GuestExcelParser.parse(unopenableFile())
        }
    }

    // ── 업로드 스트림 정리 ─────────────────────────────

    @Test
    fun `엑셀이 아닌 바이트로 열기가 실패해도 업로드 스트림은 닫힌다`() {
        val stub = CloseCountingMultipartFile("이것은 엑셀 파일이 아닙니다".toByteArray())

        assertFailsWith<GuestImportFileUnreadableException> { GuestExcelParser.parse(stub) }

        assertTrue(stub.closeCount >= 1, "손상 파일 실패 경로에서도 업로드 스트림은 닫혀야 한다")
    }

    @Test
    fun `0바이트로 열기가 실패해도 업로드 스트림은 닫힌다`() {
        // 0바이트가 던지는 IllegalArgumentException 계열은 openWorkbook의 두 catch 어디에도 걸리지 않고
        // 번역되지 않은 채 그대로 지나가는 실패다 — 정리를 catch 안이 아니라 스트림 자체에 둔 이유가
        // 바로 이 케이스다. 정리를 catch에 두는 방식이었다면 이 케이스에서는 닫히지 않는다.
        val stub = CloseCountingMultipartFile(ByteArray(0))

        assertFailsWith<IllegalArgumentException> { GuestExcelParser.parse(stub) }

        assertTrue(stub.closeCount >= 1, "번역되지 않고 지나가는 실패에서도 업로드 스트림은 닫혀야 한다")
    }

    @Test
    fun `암호가 걸린 파일로 열기가 실패해도 업로드 스트림은 닫힌다`() {
        val stub = CloseCountingMultipartFile(passwordProtectedBytes())

        assertFailsWith<GuestImportFilePasswordProtectedException> { GuestExcelParser.parse(stub) }

        assertTrue(stub.closeCount >= 1, "암호 보호 실패 경로에서도 업로드 스트림은 닫혀 있어야 한다")
    }

    @Test
    fun `정상 파일은 그대로 파싱되고 중복 close로 깨지지 않는다`() {
        val bytes = workbookBytes { sheet ->
            fillRow(sheet.createRow(0), *standardHeaders)
            fillRow(sheet.createRow(1), "김진우", "모리메이커", "대표이사", "010-1234-5678", "12가3456", "A열")
        }
        val stub = CloseCountingMultipartFile(bytes)

        val rows = GuestExcelParser.parse(stub)

        // 닫힌 스트림 뒤에 실제 셀 값을 대조한다 — 예외가 안 났다는 사실만으로는 지연 읽기 회귀를 놓친다.
        assertEquals(1, rows.size)
        assertEquals("김진우", rows[0].name)
        assertEquals("A열", rows[0].seatGroupLabel)
        assertTrue(stub.closeCount >= 1, "정상 파싱 후에도 업로드 스트림은 닫혀 있어야 한다(중복 close는 허용)")
    }
}
