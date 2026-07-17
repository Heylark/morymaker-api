package kr.co.morymaker.api.application.port.out

import java.io.InputStream
import java.nio.file.Path

/**
 * 파일 저장 경계(§11-3) — 실 구현은 `LocalFileStorageAdapter`(api-app/storage)가 담당한다.
 * 포트 경계는 JDK 타입(`InputStream`·`Path`)만 통과시킨다 — `module-application`은 Spring Web에
 * 오염되지 않는다(`MultipartFile`·`Resource`는 api-app에서 소멸).
 */
interface FileStoragePort {

    /**
     * 업로드 바이트를 검증 후 저장한다. 포맷·크기 위반 시 예외를 던지며 파일을 남기지 않는다.
     * [StoreFileCommand.source] 스트림은 호출자 소유다 — 이 포트는 close하지 않는다.
     */
    fun store(command: StoreFileCommand): StoredFile

    /** 저장된 파일의 물리 경로. 소속 행사 불일치·경로 이탈·파일 부재는 전부 null. */
    fun resolve(eventId: String, storageKey: String): Path?

    /**
     * 물리 파일 삭제(idempotent) — 부재는 정상 경로(구 메타 전용 행 재삭제 등)이므로 오류가 아니다.
     * 트랜잭션 동기화가 활성 상태면 커밋 확정 후(afterCommit)에만 실제 삭제가 수행된다.
     */
    fun delete(eventId: String, storageKey: String)
}

/** [FileStoragePort.store] 입력. */
data class StoreFileCommand(
    val eventId: String,
    /** 서버 생성 UUID — 물리 경로·파일명의 유일한 소스(사용자 입력은 경로에 관여하지 않는다). */
    val contentId: String,
    /** 메타 보존 전용 — 경로 조립에 절대 사용하지 않는다. */
    val originalName: String,
    /** 요청 신고값("영상"/"이미지") — 콘텐츠 시그니처 판별 결과와 대조하여 검증한다. */
    val declaredKind: String,
    /** 컨테이너가 실측한 바이트 수 — 클라이언트 신고값이 아니다. */
    val size: Long,
    /** 호출자 소유 — 어댑터는 close하지 않는다. */
    val source: InputStream,
)

/** [FileStoragePort.store] 결과. */
data class StoredFile(val storageKey: String, val contentType: String)
