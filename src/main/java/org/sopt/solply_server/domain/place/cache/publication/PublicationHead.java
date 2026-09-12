package org.sopt.solply_server.domain.place.cache.publication;

/**
 * 지금 발행물의 <b>머리만</b> — payload를 읽지 않고 포인터가 가리키는 행의 두 값만 가져온 것.
 *
 * <p><b>이것과 {@link PublishedSnapshot}의 차이가 존재 이유다.</b> 조회 경로가 "내가 낡았나"를
 * 물을 때 필요한 것은 번호 둘뿐인데, {@code download()}는 수 MB짜리 BLOB을 함께 끌고 온다.
 * 요청 경로에서 그 비용을 내지 않으려고 머리만 읽는 문장을 따로 둔다.
 *
 * <p><b>커서와 대조하는 값은 {@code cursorVersion}이지 {@code publicationId}가 아니다.</b>
 * 표시값만 바뀐 발행은 id만 오르고 회차는 직전 것을 이어받으므로, id로 대조하면 커서가 멀쩡한데도
 * 낡았다고 판정한다. 두 값을 함께 싣는 것은 로그에서 그 구분을 볼 수 있게 하려는 것뿐이다.
 *
 * @param publicationId 포인터가 가리키는 발행 id — 설치 진도를 재는 값
 * @param cursorVersion 그 발행물이 들고 있는 회차 — 커서가 싣고 다니는 값
 */
public record PublicationHead(long publicationId, long cursorVersion) {
}
