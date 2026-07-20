package kr.co.morymaker.api.web

import kr.co.morymaker.api.application.port.`in`.ArrivalView
import kr.co.morymaker.api.application.port.`in`.AttendanceView
import kr.co.morymaker.api.application.port.`in`.RegistrationView
import kr.co.morymaker.api.application.port.`in`.StatsParkingView
import kr.co.morymaker.api.application.port.`in`.StatsView
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayOutputStream

/**
 * 통계 Excel export(§8-2) — 4시트(지표·실참석·도착순·주차현황). 입력은 [StatsView](§8-1 집계
 * 결과 재사용, 별도 쿼리 없음). POI는 web 레이어에만 배선(application 미오염 — GuestExcelParser
 * 선례, 02-architect §7).
 */
internal object StatsExcelWriter {

    fun write(view: StatsView): ByteArray {
        XSSFWorkbook().use { wb ->
            writeIndicatorSheet(wb.createSheet("지표"), view.registration)
            writeAttendanceSheet(wb.createSheet("실참석"), view.attendance)
            writeArrivalsSheet(wb.createSheet("도착순"), view.arrivals)
            writeParkingSheet(wb.createSheet("주차현황"), view.parking)
            ByteArrayOutputStream().use { bos ->
                wb.write(bos)
                return bos.toByteArray()
            }
        }
    }

    private fun writeIndicatorSheet(sheet: Sheet, registration: RegistrationView) {
        header(sheet, "구분", "등록수", "구성비")
        dataRow(sheet, 1, "사전", registration.pre, registration.preRatio)
        dataRow(sheet, 2, "현장", registration.on, registration.onRatio)
        dataRow(sheet, 3, "합계", registration.total, null) // 합계 구성비는 항상 1.0 무의미(§8-1 정합) — 공란.
    }

    private fun writeAttendanceSheet(sheet: Sheet, attendance: AttendanceView) {
        header(sheet, "구분", "실참석", "참석률")
        dataRow(sheet, 1, "사전", attendance.preAtt, attendance.preRate)
        dataRow(sheet, 2, "현장", attendance.onAtt, attendance.onRate)
        dataRow(sheet, 3, "합계", attendance.totAtt, attendance.totRate)
    }

    private fun writeArrivalsSheet(sheet: Sheet, arrivals: List<ArrivalView>) {
        header(sheet, "이름", "도착시각")
        arrivals.forEachIndexed { index, item ->
            val row = sheet.createRow(index + 1)
            row.createCell(0).setCellValue(item.name)
            row.createCell(1).setCellValue(item.visitAt)
        }
    }

    private fun writeParkingSheet(sheet: Sheet, parking: StatsParkingView) {
        header(sheet, "구획", "총자리", "주차중", "확인필요")
        val summaryRow = sheet.createRow(1)
        summaryRow.createCell(0).setCellValue("전체 주차중")
        summaryRow.createCell(2).setCellValue(parking.parked.toDouble())
        parking.byZone.forEachIndexed { index, zone ->
            val row = sheet.createRow(index + 2)
            row.createCell(0).setCellValue(zone.zoneName)
            row.createCell(1).setCellValue(zone.slotCount.toDouble())
            row.createCell(2).setCellValue(zone.occupied.toDouble())
            row.createCell(3).setCellValue(zone.reviewNeeded.toDouble())
        }
    }

    private fun header(sheet: Sheet, vararg titles: String) {
        val row = sheet.createRow(0)
        titles.forEachIndexed { index, title -> row.createCell(index).setCellValue(title) }
    }

    /** 구성비/참석률은 raw Double 셀 그대로 유지(표시용 % 변환은 프론트 관심사 — 엑셀 원 수치 보존). */
    private fun dataRow(sheet: Sheet, rowIndex: Int, label: String, count: Int, ratioOrRate: Double?): Row {
        val row = sheet.createRow(rowIndex)
        row.createCell(0).setCellValue(label)
        row.createCell(1).setCellValue(count.toDouble())
        if (ratioOrRate != null) row.createCell(2).setCellValue(ratioOrRate)
        return row
    }
}
