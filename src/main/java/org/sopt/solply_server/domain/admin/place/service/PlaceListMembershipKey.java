package org.sopt.solply_server.domain.admin.place.service;

import java.util.Set;
import java.util.stream.Collectors;
import org.sopt.solply_server.domain.place.entity.Place;
import org.sopt.solply_server.domain.tag.entity.Tag;

/**
 * 장소 하나가 <b>목록의 어느 배열에 어떤 순서로 서는가</b>를 정하는 값들만 모은 열쇠. 어드민
 * 수정 전후로 이것이 같으면 배열은 손댈 것이 없고, 다르면 사진을 다시 찍어야 한다.
 *
 * <p><b>여기 담긴 넷이 전부인 근거.</b> 동네는 소속 배열을, 좌표는 거리순 자리를, 태그 id 집합은
 * {@code place_stats.tag_bitmask}(= 필터 결과 집합)를 정한다. 나머지 수정(이름·소개·주소·연락처·
 * 영업시간·SNS·이미지·체크포인트)은 표시값이라 배열 밖 홀더에 있다.
 *
 * <p><b>태그는 집합으로만 본다.</b> 메인·옵션 구분과 순서는 비트마스크에 남지 않으므로, 집합이
 * 같으면 필터 결과도 같다. 메인 태그가 옵션 태그와 <em>자리만 맞바꾼</em> 경우 대표 태그 이름은
 * 바뀌지만 그것은 표시값이고, 표시값 패치가 그 장소를 다시 읽어 옮긴다.
 *
 * <p><b>정렬 키(카운트·점수)는 담지 않는다.</b> 어드민이 바꾸는 값이 아니라 배치가 채우고,
 * 화면으로 옮기는 것은 10분 타이머의 전량 재빌드다.
 */
record PlaceListMembershipKey(Long townId, Double latitude, Double longitude, Set<Long> tagIds) {

    /**
     * <b>태그를 건드리기 전에 부를 것.</b> 수정 경로는 {@code Place#clearTags}로 태그를 비운 뒤
     * 다시 채우므로, 그 뒤에 읽은 "수정 전" 집합은 언제나 비어 있다.
     */
    static PlaceListMembershipKey from(final Place place) {
        return new PlaceListMembershipKey(
                place.getTown() == null ? null : place.getTown().getId(),
                place.getLatitude(),
                place.getLongitude(),
                place.getTags().stream().map(Tag::getId).collect(Collectors.toUnmodifiableSet())
        );
    }
}
