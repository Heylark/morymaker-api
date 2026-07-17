package kr.co.morymaker.api.config

import jakarta.annotation.PostConstruct
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

/**
 * prod 프로파일 기동 시 미디어 저장 루트를 검증하는 fail-fast 가드.
 *
 * 상대경로·임시 디렉토리 하위·쓰기 불가 루트로 배포되면 재배포마다 업로드 미디어가 조용히
 * 소실된다 — `ProdUrlConfigGuard`와 정확히 동형의 의도(기본값을 그대로 배포하는 사고를 기동
 * 시점에 차단)이며, 레인A(컨테이너화) 볼륨 마운트 누락을 기동 시점에 잡는 유일한 자동 장치다.
 */
@Component
@Profile("prod")
class ProdMediaRootGuard(
    private val properties: MediaProperties,
) {

    @PostConstruct
    fun validate() {
        val configured = Path.of(properties.root)
        check(configured.isAbsolute) {
            "morymaker.media.root 는 prod 프로파일에서 절대경로여야 합니다. 현재 값: '${properties.root}'"
        }

        val normalizedRoot = configured.toAbsolutePath().normalize()
        val tmpDir = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()
        check(!normalizedRoot.startsWith(tmpDir)) {
            "morymaker.media.root 는 prod 프로파일에서 임시 디렉토리 하위일 수 없습니다(재부팅 시 소실). 현재 값: '${properties.root}'"
        }

        Files.createDirectories(normalizedRoot)
        check(Files.isWritable(normalizedRoot)) {
            "morymaker.media.root 에 쓰기 권한이 없습니다: '$normalizedRoot'"
        }
    }
}
