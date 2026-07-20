package kr.co.morymaker.api.web

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.ByteArrayOutputStream

/**
 * QR PNG 생성(§6-4, P4) — zxing 위임. api-app(컨트롤러) 전용 — application 레이어에는 배선하지
 * 않는다(`GuestExcelParser`/POI 선례 정합, 02-architect §7 — QR 라이브러리 오염 회피).
 */
internal object QrCodeGenerator {

    private const val DEFAULT_SIZE = 512

    fun encode(payload: String, size: Int = DEFAULT_SIZE): ByteArray {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size, hints)
        val output = ByteArrayOutputStream()
        MatrixToImageWriter.writeToStream(matrix, "PNG", output)
        return output.toByteArray()
    }
}
