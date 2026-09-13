package org.sopt.solply_server.domain.place.cache.metadata;

/**
 * 목록 스냅샷의 <b>공유 상태 전부</b>. DB가 인스턴스들에게 알려 주는 것은 이 숫자 둘뿐이고,
 * 스냅샷의 내용은 어디에도 저장되지 않는다 — 각 인스턴스가 자기 원본을 읽어 자기 것을 짓는다.
 *
 * <p><b>둘은 다른 질문에 답한다.</b>
 * <ul>
 *   <li>{@code revision} — "다시 지을 것이 있나". 목록에 실리는 무엇이든(정렬 키·소속·이름·
 *       썸네일·태그) 바뀌면 오른다. 각 인스턴스의 폴이 자기가 설치한 값과 대조하는 값이다.
 *   <li>{@code cursorVersion} — "지금 커서를 계속 써도 되나". 진행 중인 스크롤을 끊어야 할 때만
 *       오른다. 커서 토큰이 싣고 다니는 값이고, 이것이 어긋나면 만료다.
 * </ul>
 *
 * <p><b>하나로 합치면 둘 중 하나가 망가진다.</b> revision만 두면 이름 한 칸 고친 어드민 수정이
 * 모든 스크롤 세션을 끊고, cursorVersion만 두면 "스크롤을 유지한다"를 고른 수정이 어느
 * 인스턴스에도 반영되지 않는다.
 *
 * <p>둘 다 단조 증가하고, 올리는 문장은 언제나 데이터를 고친 <b>그 트랜잭션</b> 안에 있다
 * ({@link SnapshotMetadataService}). 그래서 "데이터는 바뀌었는데 번호는 그대로"인 상태가 없다.
 */
public record SnapshotMetadata(long revision, long cursorVersion) {

    /** 아직 아무것도 설치하지 않은 인스턴스의 자리. 어떤 실제 revision보다도 작다. */
    public static final long NOT_INSTALLED = -1L;
}
