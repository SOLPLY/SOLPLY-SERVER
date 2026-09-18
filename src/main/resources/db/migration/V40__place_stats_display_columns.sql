-- V40: 스냅샷 재빌드의 원천을 place_stats 한 테이블로 좁힌다.
--
-- V34가 "목록 조회는 place_stats 하나로 끝난다"를 세웠지만 그 말이 반쪽만 참인 자리가 있었다.
-- 스냅샷 로더의 전량 문장은 이름·좌표 때문에 places와, 대표 태그 때문에 MAIN 태그 파생 테이블
-- (place_tag ⋈ tags)과 각각 조인하고, 썸네일은 아예 문장 하나(place_images 전량)를 따로 썼다.
-- DB 경로는 결과 행의 id로 엔티티를 따로 읽어 표시값을 채우니 목록 쿼리에 이름이 필요 없었지만,
-- 스냅샷은 재빌드 한 번에 표시값까지 담아야 해서 그렇다.
--
-- 이 마이그레이션은 그 조인 둘과 문장 하나를 없앤다 — 이름·좌표·메인 태그 id·썸네일 파일 키를
-- place_stats로 비정규화해 전량 문장이 SELECT ... FROM place_stats ps 단독이 되게 한다.
--
-- 다섯 컬럼 모두 인덱스를 걸지 않는다. 정렬·필터 축이 아니라 표시값이고, 로더는 전량 테이블 스캔이라
-- 커버링이 의미가 없다. main_tag_id에 FK도 걸지 않는다 — tag_bitmask와 같은 파생·표시 값이고,
-- 태그 삭제 경로가 없어 고아가 생길 길이 없으며, FK를 걸면 어드민 태그 쓰기와 upsert 사이에
-- 잠금 관계가 새로 생긴다.

-- name은 백필 대상이라 NULL로 열고 채운 뒤 NOT NULL로 조인다 — V34의 created_at과 같은 이유다.
-- NOT NULL로 바로 추가하면 기존 행이 암묵 기본값(빈 문자열)을 받아, 통과하더라도 "장소 이름"이
-- 아닌 값이 조용히 들어앉는다. 좌표와 메인 태그는 원본에서도 NULL이 정상이라 NULL 허용 그대로다.
-- thumbnail_file_key는 place_images.image_file_key와 같은 TEXT다. 원값을 그대로 담는 칸이라
-- 타입을 좁히면 긴 키가 잘려 죽은 URL이 된다. 빈 문자열도 정상 값이므로 NULL과 구분해서 보관한다 —
-- "이미지가 없다"(NULL)와 "첫 이미지의 키가 비어 있다"(빈 문자열)는 다른 상태이고, 후자에서
-- 다음 이미지로 넘어가면 엔티티 경로와 값이 갈린다.
ALTER TABLE place_stats
    ADD COLUMN name               VARCHAR(255) NULL COMMENT '장소 이름. places.name의 비정규화 사본',
    ADD COLUMN latitude           DOUBLE       NULL COMMENT '위도. places.latitude의 비정규화 사본',
    ADD COLUMN longitude          DOUBLE       NULL COMMENT '경도. places.longitude의 비정규화 사본',
    ADD COLUMN main_tag_id        BIGINT       NULL COMMENT '대표 태그 id. place_tag.id 오름차순 첫 MAIN 태그(활성 무관), 없으면 NULL',
    ADD COLUMN thumbnail_file_key TEXT         NULL COMMENT '썸네일 파일 키. place_images를 display_order, image_file_key 순으로 정렬한 첫 행의 image_file_key 원값. 이미지가 없으면 NULL';

-- ⚠️ 여기의 백필도 V32가 금지한 백필과 성질이 다르다. V32 주석이 막은 것은 bookmarks·place_reviews
-- 전량 집계로, Flyway의 REPEATABLE READ 트랜잭션이 그 두 테이블에 shared next-key 락을 걸어 동시
-- 북마크 INSERT를 ERROR 1205로 죽이는 경로였다. 아래 셋이 읽는 것은 places·place_tag·tags·
-- place_images이고 넷 다 어드민만 쓰는 저빈도 테이블이라 사용자 트래픽과 충돌하지 않는다. 그리고
-- 이 값들은 "다음 배치가 채우면 된다"로 미룰 수 없다 — name이 NOT NULL이고, 표시값이 빈 채로
-- 서빙되면 목록의 이름·대표 태그·썸네일이 어드민이 그 장소를 다시 저장할 때까지 통째로 빈다.
UPDATE place_stats ps
    JOIN places p ON p.id = ps.place_id
SET ps.name      = p.name,
    ps.latitude  = p.latitude,
    ps.longitude = p.longitude;

-- 대표 태그 규칙: place_tag.id 오름차순 첫 MAIN 태그, 활성 무관. 태그의 활성 여부로 거르지 않는
-- 것이 계약이다 — 여기서 걸러내면 그 다음 MAIN 태그가 뽑혀 엔티티 경로와 값이 갈린다.
-- 활성 판정은 이름을 실을지 정하는 응답 조립의 몫이다.
UPDATE place_stats ps
    JOIN (SELECT pt.place_id, MIN(pt.id) AS pt_id
          FROM place_tag pt
                   JOIN tags t ON t.id = pt.tag_id
          WHERE t.type = 'MAIN'
          GROUP BY pt.place_id) mm ON mm.place_id = ps.place_id
    JOIN place_tag mpt ON mpt.id = mm.pt_id
SET ps.main_tag_id = mpt.tag_id;

-- 썸네일 규칙: place_images를 display_order, image_file_key 순으로 정렬한 첫 행의 파일 키 원값.
-- image_file_key가 타이브레이커인 것은 이 테이블에 대리키가 없고 display_order가 중복도 NULL도
-- 허용하기 때문이다 — 값으로 갈라야 전량과 단건이 같은 이미지를 고른다. 이미지가 없는 장소는
-- 서브쿼리가 NULL을 내고 그것이 "썸네일 없음"이다.
UPDATE place_stats ps
SET ps.thumbnail_file_key = (SELECT pi.image_file_key
                             FROM place_images pi
                             WHERE pi.place_id = ps.place_id
                             ORDER BY pi.display_order, pi.image_file_key
                             LIMIT 1);

ALTER TABLE place_stats
    MODIFY COLUMN name VARCHAR(255) NOT NULL COMMENT '장소 이름. places.name의 비정규화 사본';
