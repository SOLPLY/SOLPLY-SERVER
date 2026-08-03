// 측정 3 (한계 탐색) 전용 — 북마크 토글 flow에서 POST와 DELETE가 같은 placeId를 쓰도록 고정한다.
// $randomNumber는 참조마다 재평가되어 두 요청이 다른 장소를 가리키게 되기 때문.
// placeId 분포는 균등(1~6000) — 핫 플레이스 집중 경합은 2026-07-31 Task 8 실측이 커버한다.
module.exports = {
  pickPlace: function (context, events, done) {
    context.vars.placeId = 1 + Math.floor(Math.random() * 6000);
    return done();
  },
};
