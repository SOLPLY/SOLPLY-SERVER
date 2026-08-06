#!/usr/bin/env node
// 인기순 벤치 시드 제너레이터 — SQL을 stdout으로 스트리밍
// 사용: node generate-popular-seed.mjs | docker exec -i solply-bench-mysql mysql -usolplyuser -psolplyuserpwd solply_bench_db
// 전제: 기존 #379 시드(bench 유저 10만)가 선행 실행돼 있어야 한다 (run-seed-popular.sh가 보장)
//
// 분포 설계 (설계 합의):
// - 동네 축 편중: 핫플 동네(강남·홍대·건대성수)에 북마크 집중
// - 장소 축 Zipf: 동네 내 rank^-0.9
// - 유저 축 멱법칙: 상위 1% 헤비(평균 400개) / 9% 미드(100개) / 90% 라이트(30개) ≈ 총 400만
// - 시간 축 편향: 트렌딩 장소 12곳은 북마크의 45%가 최근 7일 (소재 2 겸용)

const SEED_TOWNS = [
  // [id, parentId, name]
  [101, null, '서울 전체'],
  [102, 101, '강남'], [103, 101, '서초'], [104, 101, '잠실·송파·강동'],
  [105, 101, '영등포·여의도·강서'], [106, 101, '건대·성수·왕십리'], [107, 101, '종로·중구'],
  [108, 101, '홍대·합정·마포'], [109, 101, '용산·이태원·한남'], [110, 101, '성북·노원·중랑'],
  [111, 101, '구로·관악·동작'],
  [121, null, '경기 전체'],
  [122, 121, '의왕·안양·군포'], [123, 121, '용인·화성·평택'], [124, 121, '부천·시흥·안산'],
  [125, 121, '성남·하남'], [126, 121, '수원'],
  [131, null, '인천'], // root이자 leaf — 장소가 직접 붙는다
];
const LEAF_IDS = [102, 103, 104, 105, 106, 107, 108, 109, 110, 111, 122, 123, 124, 125, 126, 131];

// 동네 축 가중치 (합 1.0) — 핫플 편중
const TOWN_WEIGHTS = {
  102: 0.16, 108: 0.14, 106: 0.12, 104: 0.09, 103: 0.07, 105: 0.06, 107: 0.06,
  109: 0.06, 110: 0.04, 111: 0.04, 122: 0.02, 123: 0.03, 124: 0.03, 125: 0.03,
  126: 0.03, 131: 0.02,
};

// 태그 체계 (V2 시드 기준): main → {opt1[], opt2[]}
const TAG_MAP = {
  1: { opt1: [7, 8, 9, 10], opt2: [11, 12, 13, 14, 15, 16] }, // 카페
  2: { opt1: [17, 18, 19, 20, 21, 22, 23], opt2: [24, 25] },   // 음식
  3: { opt1: [28, 29, 30], opt2: [] },                          // 쇼핑
  4: { opt1: [], opt2: [] },                                    // 서점/책방
  5: { opt1: [26, 27], opt2: [] },                              // 이색공간
  6: { opt1: [], opt2: [] },                                    // 산책
};
const MAIN_TAGS = [1, 2, 3, 4, 5, 6];

const TOTAL_USERS = 100_000;
const PLACE_ID_BASE = 10_001;
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
out(`DELETE FROM place_tag WHERE place_id >= ${PLACE_ID_BASE};`);
out(`DELETE FROM place_images WHERE place_id >= ${PLACE_ID_BASE};`);
out(`DELETE FROM places WHERE id >= ${PLACE_ID_BASE};`);
out('DELETE FROM towns WHERE id BETWEEN 101 AND 131;');
out('SET FOREIGN_KEY_CHECKS = 1;');

// ---------- 2) towns ----------
const townRows = SEED_TOWNS.map(([id, pid, name]) =>
  `(${id}, ${pid === null ? 'NULL' : pid}, '${esc(name)}', true)`);
out(`INSERT INTO towns (id, parent_id, name, active) VALUES\n${townRows.join(',\n')};`);

// ---------- 3) places + place_tag + place_images ----------
// 동네마다 40~50개, created_at은 과거 18개월 균등
const places = []; // {id, townId, rank, mainTag}
let nextPlaceId = PLACE_ID_BASE;
for (const townId of LEAF_IDS) {
  const count = 40 + rand(11);
  for (let r = 1; r <= count; r++) {
    places.push({ id: nextPlaceId++, townId, rank: r, mainTag: pick(MAIN_TAGS) });
  }
}

const placeRows = places.map((p) => {
  const created = NOW - rand(548 * DAY);
  return `(${p.id}, '벤치플레이스 ${p.id}', '인기순 벤치마크용 합성 장소', '서울시 어딘가 ${p.id}', ${p.townId}, 1, true, '${sqlDate(created)}', '${sqlDate(created)}')`;
});
for (let i = 0; i < placeRows.length; i += BATCH) {
  out(`INSERT INTO places (id, name, introduction, address, town_id, created_by, active, created_at, updated_at) VALUES\n${placeRows.slice(i, i + BATCH).join(',\n')};`);
}

const tagRows = [];
for (const p of places) {
  tagRows.push(`(${p.id}, ${p.mainTag})`);
  const { opt1, opt2 } = TAG_MAP[p.mainTag];
  const picked = new Set();
  const n1 = opt1.length === 0 ? 0 : 1 + rand(Math.min(2, opt1.length));
  while (picked.size < n1) picked.add(pick(opt1));
  const n2 = opt2.length === 0 ? 0 : rand(Math.min(3, opt2.length) + 1);
  const picked2 = new Set();
  while (picked2.size < n2) picked2.add(pick(opt2));
  for (const t of picked) tagRows.push(`(${p.id}, ${t})`);
  for (const t of picked2) tagRows.push(`(${p.id}, ${t})`);
}
for (let i = 0; i < tagRows.length; i += BATCH) {
  out(`INSERT INTO place_tag (place_id, tag_id) VALUES\n${tagRows.slice(i, i + BATCH).join(',\n')};`);
}

const imageRows = places.map((p) => `(${p.id}, 'bench/popular/p${p.id}.jpg', 1)`);
for (let i = 0; i < imageRows.length; i += BATCH) {
  out(`INSERT INTO place_images (place_id, image_file_key, display_order) VALUES\n${imageRows.slice(i, i + BATCH).join(',\n')};`);
}

// ---------- 4) 장소 가중치 누적 분포 (동네 가중치 × 동네 내 Zipf) ----------
const weights = places.map((p) => TOWN_WEIGHTS[p.townId] / Math.pow(p.rank, 0.9));
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

// 트렌딩 12곳: 가중치 상위 100 중 랜덤 (소재 2 겸용 — 최근 7일 집중)
const top100 = [...places.keys()].sort((a, b) => weights[b] - weights[a]).slice(0, 100);
const trendingIdx = new Set();
while (trendingIdx.size < 12) trendingIdx.add(pick(top100));
const trendingIds = new Set([...trendingIdx].map((i) => places[i].id));

// ---------- 5) bookmarks ~400만 ----------
// bench 유저 id는 연속이 아닐 수 있으므로 rn(정렬 순번) 기반 임시 테이블로 매핑한다.
out(`CREATE TEMPORARY TABLE tmp_bench_users AS
  SELECT id, ROW_NUMBER() OVER (ORDER BY id) AS rn
  FROM users WHERE email LIKE 'bench\\_%@bench.local';`);
out('CREATE INDEX idx_tmp_rn ON tmp_bench_users (rn);');

function bookmarkCountFor(rn) {
  if (rn <= TOTAL_USERS * 0.01) return 300 + rand(201);   // 헤비 1%: 300~500
  if (rn <= TOTAL_USERS * 0.10) return 60 + rand(81);     // 미드 9%: 60~140
  return 15 + rand(31);                                    // 라이트 90%: 15~45
}
function bookmarkTimestamp(placeId) {
  if (trendingIds.has(placeId) && Math.random() < 0.45) {
    return NOW - rand(7 * DAY);                            // 트렌딩: 최근 7일
  }
  return NOW - Math.floor(365 * DAY * Math.pow(Math.random(), 1.6)); // 완만한 최근 편향
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

// ---------- 6) 검증 출력 ----------
out(`SELECT COUNT(*) AS popular_bookmarks FROM bookmarks WHERE target_type='PLACE' AND target_id >= ${PLACE_ID_BASE};`);
out(`SELECT b.target_id, COUNT(*) c FROM bookmarks b WHERE b.target_type='PLACE' AND b.target_id >= ${PLACE_ID_BASE} GROUP BY b.target_id ORDER BY c DESC LIMIT 10;`);
out('ANALYZE TABLE bookmarks;');
out('ANALYZE TABLE places;');
process.stderr.write(`[generate] places=${places.length}, bookmarks=${total}, trending=${[...trendingIds].join(',')}\n`);
