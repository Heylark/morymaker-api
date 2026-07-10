package kr.co.morymaker.api.security

import kr.co.morymaker.api.application.port.out.FileStoragePort
import kr.co.morymaker.api.application.port.out.StoreFileCommand
import kr.co.morymaker.api.application.port.out.StoredFile
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * [FileStoragePort] 스텁 구현체 — 외부 스토리지·네트워크 0(로그 1줄만 남긴다). 실 저장은
 * 계약 후 이 어댑터만 교체한다(§11-3 범위 외 이연 — 사용자 확정).
 *
 * 헥사고날 레이어: api-app(adapter) — `SmsSenderStubAdapter`와 동일하게 비-DB `@Component`로
 * 배치한다(단일 스텁을 위한 신규 패키지 신설은 과잉 구조). `internal`: application
 * 계층은 [FileStoragePort] 인터페이스만 의존한다.
 */
@Component
internal class FileStorageStubAdapter : FileStoragePort {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun store(command: StoreFileCommand): StoredFile {
        log.info("파일 저장(스텁) event={} name={} kind={}", command.eventId.take(8), command.name, command.kind)
        return StoredFile(fileUrl = null)
    }
}
