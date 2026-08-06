// 측정 3 (한계 탐색) 전용 — 북마크 토글 flow에서 POST와 DELETE가 같은 placeId를 쓰도록 고정한다.
// $randomNumber는 참조마다 재평가되어 두 요청이 다른 장소를 가리키게 되기 때문.
//
// 2026-08-03 수정 (1차 측정 결함 복구, 사용자 승인).
//   종전: `1 + Math.floor(Math.random() * 6000)` — placeId를 1~6000 균등 추출.
//   문제: 벤치 시드의 실제 `places.id`는 1~16000에 퍼져 있고 1~6000 구간에는 6,320개 중
//         320개(5.1%)만 있다. 그래서 POST의 94.7%가 404로 떨어지고 라운드 중
//         `Innodb_rows_updated`가 0 — `place_stats` 증분 UPDATE가 한 번도 실행되지 않았다.
//         "증분 UPDATE와 인기순 SELECT가 같은 place_stats에서 만난다"는 측정 3의 전제가
//         재현되지 않은 것이다.
//   수정: 실제 활성 장소 id 목록(place-ids.json — 벤치 DB에서 덤프한 6,320개)에서 균등 추출.
//
// 균등성 전제는 그대로다 — 캠페인 README "말할 수 없는 것" 7번(핫 플레이스 집중 경합은
// 2026-07-31 Task 8 실측이 커버한다)이 유지된다. 바뀐 것은 "무엇에 대해 균등한가"이고,
// 존재하지 않는 1~6000 구간이 아니라 실제 장소 집합에 대해 균등해졌다.
const PLACE_IDS = require('./place-ids.json');

module.exports = {
  pickPlace: function (context, events, done) {
    context.vars.placeId = PLACE_IDS[Math.floor(Math.random() * PLACE_IDS.length)];
    return done();
  },
};
