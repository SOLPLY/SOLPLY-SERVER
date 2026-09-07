package org.sopt.solply_server.domain.bookmark.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.*;

/**
 * 북마크 카운트 아웃박스의 전표 한 장.
 *
 * <p><b>이 행은 도메인 상태가 아니다.</b> 소비되면 사라지는 기록이라, 조회하거나 참조하는
 * 코드가 생기면 안 된다 — 쓰는 쪽은 북마크 등록·해제 트랜잭션이고 읽는 쪽은 카운트 배치
 * 하나뿐이며, 배치는 적용과 동시에 자기가 읽은 행을 지운다.
 *
 * <p><b>{@code createdAt}은 소비 로직이 쓰지 않는다.</b> 소비 순서와 삭제 대상은 오로지 id가
 * 정한다. 이 값은 "전표가 얼마나 밀렸는가"를 운영에서 눈으로 보기 위한 칸이다. 시각으로
 * 워터마크를 잡으면 안 된다 — 커밋 순서가 시각 순서와 다를 수 있어 미커밋 전표를 적용 없이
 * 지우는 유실 경로가 열린다.
 *
 * <p>{@link org.sopt.solply_server.global.entity.BaseTimeEntity}를 상속하지 않는다 —
 * 전표는 수정되지 않으므로 {@code updated_at} 칸이 스키마에 없다.
 */
@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Table(name = "bookmark_count_events")
public class BookmarkCountEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 어떤 도메인의 카운트인지 (지금 발행되는 것은 PLACE뿐) */
    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 30)
    private BookmarkTargetType targetType;

    /** 대상 엔티티의 ID */
    @Column(name = "target_id", nullable = false)
    private Long targetId;

    /** +1 = 등록, −1 = 해제. 배치는 대상별 합으로 접어 한 번에 더한다. */
    @Column(name = "delta", nullable = false, columnDefinition = "TINYINT")
    private int delta;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public static BookmarkCountEvent increment(BookmarkTargetType targetType, Long targetId) {
        return of(targetType, targetId, 1);
    }

    public static BookmarkCountEvent decrement(BookmarkTargetType targetType, Long targetId) {
        return of(targetType, targetId, -1);
    }

    private static BookmarkCountEvent of(BookmarkTargetType targetType, Long targetId, int delta) {
        return BookmarkCountEvent.builder()
                .targetType(targetType)
                .targetId(targetId)
                .delta(delta)
                .createdAt(LocalDateTime.now())
                .build();
    }
}
