package kr.co.morymaker.api.storage

/**
 * 업로드 콘텐츠의 선두 바이트로 실 포맷을 판별한다(§11-3 확장자 위장 차단) — 확장자·클라이언트
 * 신고 Content-Type은 신뢰하지 않고, 파일 내용 자체의 시그니처만 근거로 삼는다.
 *
 * 컨테이너 시그니처 한정 검증이다 — 코덱(H.264 등) 판별은 하지 않는다. 검증의 목적은 확장자
 * 위장 차단이지 코덱 정책 집행이 아니며, 코덱 판별은 mp4 박스 트리 파싱이 필요해 신규
 * 의존성 또는 200MB 파싱을 부른다(02-architect.md ADR-011).
 *
 * 신규 의존성 0건 — 대상 5개 포맷이 전부 선두 고정 오프셋이라 순수 Kotlin 16바이트 검사로 충분하다.
 */
internal object MediaSignature {

    enum class Format(val mimeType: String, val kind: String, val maxBytes: Long) {
        PNG("image/png", "이미지", IMAGE_MAX_BYTES),
        JPEG("image/jpeg", "이미지", IMAGE_MAX_BYTES),
        WEBP("image/webp", "이미지", IMAGE_MAX_BYTES),
        MP4("video/mp4", "영상", VIDEO_MAX_BYTES),
        WEBM("video/webm", "영상", VIDEO_MAX_BYTES),
    }

    /** 선두 [HEAD_BYTES]바이트로 포맷을 판별한다. 어떤 시그니처와도 일치하지 않으면 null. */
    fun detect(head: ByteArray): Format? = when {
        head.startsWith(PNG_SIGNATURE, 0) -> Format.PNG
        head.startsWith(JPEG_SIGNATURE, 0) -> Format.JPEG
        // WebP는 RIFF(0)만으로 판별하면 AVI(RIFF+AVI )를 오판한다 — 오프셋 8의 WEBP까지 확인 필수.
        head.startsWith(RIFF_SIGNATURE, 0) && head.startsWith(WEBP_SIGNATURE, 8) -> Format.WEBP
        // MP4 ftyp 박스는 오프셋 4 — 선두 4바이트는 박스 크기(길이 필드)다.
        head.startsWith(FTYP_SIGNATURE, 4) -> Format.MP4
        head.startsWith(WEBM_SIGNATURE, 0) -> Format.WEBM
        else -> null
    }

    private fun ByteArray.startsWith(signature: ByteArray, offset: Int): Boolean {
        if (this.size < offset + signature.size) return false
        return signature.indices.all { this[offset + it] == signature[it] }
    }

    const val HEAD_BYTES = 16

    private val PNG_SIGNATURE =
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val JPEG_SIGNATURE =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val RIFF_SIGNATURE = "RIFF".toByteArray(Charsets.US_ASCII)
    private val WEBP_SIGNATURE = "WEBP".toByteArray(Charsets.US_ASCII)
    private val FTYP_SIGNATURE = "ftyp".toByteArray(Charsets.US_ASCII)
    private val WEBM_SIGNATURE = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())

    private const val IMAGE_MAX_BYTES = 20L * 1024 * 1024
    private const val VIDEO_MAX_BYTES = 200L * 1024 * 1024
}
