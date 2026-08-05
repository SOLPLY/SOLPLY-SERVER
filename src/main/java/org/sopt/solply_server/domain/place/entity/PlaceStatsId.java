package org.sopt.solply_server.domain.place.entity;

import java.io.Serializable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * {@link PlaceStats}의 복합 PK — (장소, 버전).
 *
 * <p>record가 아니라 클래스인 것은 {@code @IdClass}가 <b>no-arg 생성자</b>를 요구하기 때문이다.
 * 필드 이름·타입은 엔티티의 {@code @Id} 필드와 정확히 같아야 한다.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class PlaceStatsId implements Serializable {

    private Long placeId;

    private Long version;
}
