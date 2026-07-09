package kr.co.morymaker.api.application.service

import org.springframework.stereotype.Component
import java.security.SecureRandom

/**
 * 참석자 체크인 토큰 생성기 — URL-safe 32자 base62 `SecureRandom`.
 *
 * 토큰 추측 불가성이 유일한 방어선인 영역(예: 향후 무인증 키오스크 경로)이 있을 수 있어 반드시
 * `SecureRandom` 사용 — `Random`이나 UUID 앞자리 절단 금지.
 */
@Component
class GuestTokenGenerator {

    private val random = SecureRandom()

    fun generate(): String = buildString(TOKEN_LENGTH) {
        repeat(TOKEN_LENGTH) {
            append(ALPHABET[random.nextInt(ALPHABET.length)])
        }
    }

    companion object {
        private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        private const val TOKEN_LENGTH = 32
    }
}
