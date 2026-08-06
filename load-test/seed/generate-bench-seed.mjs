#!/usr/bin/env node
// 벤치 시드 제너레이터 — SQL을 stdout으로 스트리밍 (2026-07-30 재작성)
// 사용: node generate-bench-seed.mjs | docker exec -i solply-bench-mysql mysql -usolplyuser -psolplyuserpwd solply_bench_db
//
// 규모 근거: docs/superpowers/specs/2026-07-30-place-popular-score-design.md §1.2
//   leaf town 60 / 장소 6,000 (leaf당 100) / 유저 10만 / 북마크 1,000만 (유저당 평균 100)
//
// 분포 설계:
// - 동네 축: 수도권 편중 (서울 leaf 18개가 전체 가중치의 55%)
// - 장소 축: 동네 내 Zipf(0.9) — 1위와 최하위가 약 13배
// - 유저 축: 멱법칙 (헤비 1% / 미드 9% / 라이트 90%)
// - 시간 축: 최근 편향 + 트렌딩 20곳은 북마크의 45%가 최근 7일
//            → 반감기 90일 감쇠 점수가 실제로 순위를 바꾸는지 검증하기 위함

const ROOTS = [
  [201, '서울'], [202, '경기'], [203, '인천'], [204, '부산'], [205, '대구'],
  [206, '대전'], [207, '광주'], [208, '울산'], [209, '세종'], [210, '강원'],
  [211, '충북'], [212, '충남'], [213, '전북'], [214, '전남'], [215, '경북'],
  [216, '경남'], [217, '제주'],
];

// [leafId, rootId, name, 동네 가중치]
const LEAVES = [
  [301, 201, '강남', 0.075], [302, 201, '서초', 0.030], [303, 201, '잠실·송파', 0.035],
  [304, 201, '강동', 0.015], [305, 201, '여의도·영등포', 0.025], [306, 201, '건대·성수', 0.060],
  [307, 201, '왕십리', 0.015], [308, 201, '종로', 0.030], [309, 201, '중구·을지로', 0.030],
  [310, 201, '홍대·합정', 0.065], [311, 201, '연남·망원', 0.045], [312, 201, '서촌', 0.020],
  [313, 201, '북촌', 0.020], [314, 201, '이태원·한남', 0.035], [315, 201, '용산', 0.020],
  [316, 201, '성북', 0.010], [317, 201, '노원', 0.010], [318, 201, '관악·신림', 0.010],
  [319, 202, '판교', 0.020], [320, 202, '분당', 0.018], [321, 202, '수원·행궁동', 0.018],
  [322, 202, '일산', 0.014], [323, 202, '안양·평촌', 0.010], [324, 202, '하남·미사', 0.010],
  [325, 203, '인천 개항로', 0.012], [326, 203, '송도', 0.012],
  [327, 204, '해운대', 0.020], [328, 204, '서면', 0.016], [329, 204, '광안리', 0.016],
  [330, 204, '남포동', 0.010], [331, 204, '전포', 0.012],
  [332, 205, '동성로', 0.010], [333, 205, '수성', 0.007],
  [334, 206, '둔산', 0.008], [335, 206, '은행동', 0.006],
  [336, 207, '충장로', 0.007], [337, 207, '동명동', 0.007],
  [338, 208, '삼산', 0.006], [339, 209, '나성동', 0.005],
  [340, 210, '강릉', 0.014], [341, 210, '속초', 0.012], [342, 210, '춘천', 0.009],
  [343, 210, '양양', 0.009],
  [344, 211, '청주', 0.007], [345, 212, '천안', 0.007], [346, 212, '공주', 0.005],
  [347, 213, '전주 한옥마을', 0.014], [348, 213, '군산', 0.006],
  [349, 214, '여수', 0.012], [350, 214, '순천', 0.007], [351, 214, '목포', 0.006],
  [352, 215, '경주', 0.012], [353, 215, '포항', 0.007], [354, 215, '안동', 0.006],
  [355, 216, '창원', 0.007], [356, 216, '통영', 0.008], [357, 216, '거제', 0.007],
  [358, 217, '제주시', 0.016], [359, 217, '애월', 0.014], [360, 217, '서귀포', 0.012],
];

// 태그 체계 (V2 시드 기준): main → {opt1[], opt2[]}
const TAG_MAP = {
  1: { opt1: [7, 8, 9, 10], opt2: [11, 12, 13, 14, 15, 16] }, // 카페
  2: { opt1: [17, 18, 19, 20, 21, 22, 23], opt2: [24, 25] },  // 음식
  3: { opt1: [28, 29, 30], opt2: [] },                        // 쇼핑
  4: { opt1: [], opt2: [] },                                  // 서점/책방
  5: { opt1: [26, 27], opt2: [] },                            // 이색공간
  6: { opt1: [], opt2: [] },                                  // 산책
};
const MAIN_TAGS = [1, 2, 3, 4, 5, 6];

const TOTAL_USERS = 100_000;
const PLACES_PER_LEAF = 100;
const PLACE_ID_BASE = 10_001;
const TOWN_ID_MIN = 201;
const TOWN_ID_MAX = 360;
const BATCH = 5_000;
const DAY = 86_400;
const NOW = Math.floor(Date.now() / 1000);

const out = (s) => process.stdout.write(s + '\n');
const esc = (s) => s.replace(/'/g, "''");
const rand = (n) => Math.floor(Math.random() * n);
const pick = (arr) => arr[rand(arr.length)];
const sqlDate = (epoch) => new Date(epoch * 1000).toISOString().slice(0, 19).replace('T', ' ');

// ---------- 1) 정리 (멱등) ----------
out('SET FOREIGN_KEY_CHECKS = 0;');
out(`DELETE FROM bookmarks WHERE target_type = 'PLACE' AND target_id >= ${PLACE_ID_BASE};`);
out(`DELETE b FROM bookmarks b JOIN users u ON u.id = b.user_id WHERE u.email LIKE 'bench\\_%@bench.local';`);
out(`DELETE FROM place_tag WHERE place_id >= ${PLACE_ID_BASE};`);
out(`DELETE FROM place_images WHERE place_id >= ${PLACE_ID_BASE};`);
out(`DELETE FROM places WHERE id >= ${PLACE_ID_BASE};`);
out(`DELETE FROM towns WHERE id BETWEEN ${TOWN_ID_MIN} AND ${TOWN_ID_MAX};`);
out(`DELETE FROM users WHERE email LIKE 'bench\\_%@bench.local';`);
out('SET FOREIGN_KEY_CHECKS = 1;');

// ---------- 2) towns ----------
const townRows = [
  ...ROOTS.map(([id, name]) => `(${id}, NULL, '${esc(name)}', true)`),
  ...LEAVES.map(([id, root, name]) => `(${id}, ${root}, '${esc(name)}', true)`),
];
out(`INSERT INTO towns (id, parent_id, name, active) VALUES\n${townRows.join(',\n')};`);

// ---------- 3) users 10만 ----------
out('SET @@cte_max_recursion_depth = 200000;');
out(`INSERT INTO users (role, nickname, email, is_new_user, is_deleted)
WITH RECURSIVE seq AS (SELECT 1 AS n UNION ALL SELECT n + 1 FROM seq WHERE n < ${TOTAL_USERS})
SELECT 'USER', CONCAT('bench_', n), CONCAT('bench_', n, '@bench.local'), FALSE, FALSE FROM seq;`);

// ---------- 4) places 6,000 + place_tag + place_images ----------
const places = []; // {id, townId, rank, mainTag}
let nextPlaceId = PLACE_ID_BASE;
for (const [leafId] of LEAVES) {
  for (let r = 1; r <= PLACES_PER_LEAF; r++) {
    places.push({ id: nextPlaceId++, townId: leafId, rank: r, mainTag: pick(MAIN_TAGS) });
  }
}

const placeRows = places.map((p) => {
  const created = NOW - rand(548 * DAY); // 과거 18개월 균등
  return `(${p.id}, '벤치플레이스 ${p.id}', '벤치마크용 합성 장소', '주소 ${p.id}', ${p.townId}, 1, true, '${sqlDate(created)}', '${sqlDate(created)}')`;
});
for (let i = 0; i < placeRows.length; i += BATCH) {
  out(`INSERT INTO places (id, name, introduction, address, town_id, created_by, active, created_at, updated_at) VALUES\n${placeRows.slice(i, i + BATCH).join(',\n')};`);
}

const tagRows = [];
for (const p of places) {
  tagRows.push(`(${p.id}, ${p.mainTag})`);
  const { opt1, opt2 } = TAG_MAP[p.mainTag];
  const picked1 = new Set();
  const n1 = opt1.length === 0 ? 0 : 1 + rand(Math.min(2, opt1.length));
  while (picked1.size < n1) picked1.add(pick(opt1));
  const picked2 = new Set();
  const n2 = opt2.length === 0 ? 0 : rand(Math.min(3, opt2.length) + 1);
  while (picked2.size < n2) picked2.add(pick(opt2));
  for (const t of picked1) tagRows.push(`(${p.id}, ${t})`);
  for (const t of picked2) tagRows.push(`(${p.id}, ${t})`);
}
for (let i = 0; i < tagRows.length; i += BATCH) {
  out(`INSERT INTO place_tag (place_id, tag_id) VALUES\n${tagRows.slice(i, i + BATCH).join(',\n')};`);
}

const imageRows = places.map((p) => `(${p.id}, 'bench/p${p.id}.jpg', 1)`);
for (let i = 0; i < imageRows.length; i += BATCH) {
  out(`INSERT INTO place_images (place_id, image_file_key, display_order) VALUES\n${imageRows.slice(i, i + BATCH).join(',\n')};`);
}

// ---------- 5) 장소 가중치 누적 분포 (동네 가중치 × 동네 내 Zipf) ----------
const leafWeight = Object.fromEntries(LEAVES.map(([id, , , w]) => [id, w]));
const weights = places.map((p) => leafWeight[p.townId] / Math.pow(p.rank, 0.9));
const totalW = weights.reduce((a, b) => a + b, 0);
const cum = [];
let acc = 0;
for (const w of weights) { acc += w / totalW; cum.push(acc); }
function samplePlace() {
  const u = Math.random();
  let lo = 0, hi = cum.length - 1;
  while (lo < hi) { const mid = (lo + hi) >> 1; if (cum[mid] < u) lo = mid + 1; else hi = mid; }
  return places[lo];
}

// 트렌딩 20곳: 가중치 상위 200 중 랜덤 — 감쇠 점수 검증용
const top200 = [...places.keys()].sort((a, b) => weights[b] - weights[a]).slice(0, 200);
const trendingIdx = new Set();
while (trendingIdx.size < 20) trendingIdx.add(pick(top200));
const trendingIds = new Set([...trendingIdx].map((i) => places[i].id));

// ---------- 6) bookmarks 약 1,000만 ----------
// 유저당 평균 100개: 헤비 1%(400~600) + 미드 9%(150~250) + 라이트 90%(70~110)
//   = 1,000×500 + 9,000×200 + 90,000×90 = 500,000 + 1,800,000 + 8,100,000 ≈ 10,400,000
out(`CREATE TEMPORARY TABLE tmp_bench_users AS
  SELECT id, ROW_NUMBER() OVER (ORDER BY id) AS rn
  FROM users WHERE email LIKE 'bench\\_%@bench.local';`);
out('CREATE INDEX idx_tmp_rn ON tmp_bench_users (rn);');

function bookmarkCountFor(rn) {
  if (rn <= TOTAL_USERS * 0.01) return 400 + rand(201);
  if (rn <= TOTAL_USERS * 0.10) return 150 + rand(101);
  return 70 + rand(41);
}
function bookmarkTimestamp(placeId) {
  if (trendingIds.has(placeId) && Math.random() < 0.45) {
    return NOW - rand(7 * DAY);
  }
  return NOW - Math.floor(548 * DAY * Math.pow(Math.random(), 1.6));
}

let batch = [];
let total = 0;
function flush() {
  if (batch.length === 0) return;
  out(`INSERT INTO bookmarks (user_id, target_type, target_id, created_at)
SELECT u.id, 'PLACE', v.pid, v.ts FROM (VALUES\n${batch.join(',\n')}\n) AS v(rn, pid, ts)
JOIN tmp_bench_users u ON u.rn = v.rn;`);
  batch = [];
}
for (let rn = 1; rn <= TOTAL_USERS; rn++) {
  const n = bookmarkCountFor(rn);
  const seen = new Set();
  let attempts = 0;
  while (seen.size < n && attempts < n * 20) {
    attempts++;
    const p = samplePlace();
    if (seen.has(p.id)) continue;
    seen.add(p.id);
    batch.push(`ROW(${rn}, ${p.id}, '${sqlDate(bookmarkTimestamp(p.id))}')`);
    total++;
    if (batch.length >= BATCH) flush();
  }
}
flush();
out('DROP TEMPORARY TABLE tmp_bench_users;');

// ---------- 7) 검증 출력 ----------
out(`SELECT COUNT(*) AS bench_users FROM users WHERE email LIKE 'bench\\_%@bench.local';`);
out(`SELECT COUNT(*) AS bench_places FROM places WHERE id >= ${PLACE_ID_BASE};`);
out(`SELECT COUNT(*) AS bench_bookmarks FROM bookmarks WHERE target_type='PLACE' AND target_id >= ${PLACE_ID_BASE};`);
out(`SELECT t.name, COUNT(*) c FROM bookmarks b
     JOIN places p ON p.id = b.target_id JOIN towns t ON t.id = p.town_id
     WHERE b.target_type='PLACE' AND b.target_id >= ${PLACE_ID_BASE}
     GROUP BY t.name ORDER BY c DESC LIMIT 10;`);
out('ANALYZE TABLE bookmarks;');
out('ANALYZE TABLE places;');
process.stderr.write(`[generate] places=${places.length}, bookmarks=${total}, trending=${[...trendingIds].join(',')}\n`);
