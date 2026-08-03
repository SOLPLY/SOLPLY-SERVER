#!/usr/bin/env node
// 리뷰 시드 제너레이터 — SQL을 stdout으로 스트리밍 (2026-07-31, Task 8)
//
// 사용: node generate-review-seed.mjs <place-dist.tsv>
//   TSV 형식: place_id \t bookmark_count \t recent7_count   (run-review-seed.sh가 DB에서 뽑아 넘긴다)
//
// 기존 벤치 시드(2026-07-30_place-popular-baseline)에 **추가**하는 형태다.
// 유저·장소·북마크를 만들지 않고 이미 있는 것을 쓴다. 리뷰만 지우고 다시 넣으므로 멱등하다.
//
// ── 규모: 20만 ──────────────────────────────────────────────────────────
// 설계 §1.2 산정표 값. 유저 10만 × 2건, 교차검증 DAU 2만 × 0.5%/일 → 3년 11만.
// 설계 §2.6에 인용된 "리뷰 30만"은 버퍼풀 1G·시드 미보존으로 폐기된 측정의 환경값이라
// 맞출 대상이 아니다. 20만이라야 설계의 근거인 리뷰/북마크 = 1/50 비대칭도 유지된다.
// (30만이면 1/35가 되어 "북마크는 탭 1회, 리뷰는 방문+사진+500자"라는 논거와 어긋난다)
//
// ── 분포 설계 ───────────────────────────────────────────────────────────
// 장소 축: **북마크 실측 분포에 비례.** Zipf를 새로 정의하지 않고 DB에서 유도하는 이유는
//   두 축이 같은 인기 분포를 따라야 감쇠 점수 검증이 의미를 갖기 때문이다.
//   리뷰 축만 균등하면 복합 점수에서 리뷰항이 사실상 상수가 되어 아무것도 검증하지 못한다.
// 시간 축: 북마크 시드와 같은 최근 편향(pow 1.6) + 트렌딩 장소는 45%를 최근 7일에.
//   트렌딩 판정도 하드코딩하지 않고 **북마크의 최근 7일 비율 > 30%** 로 DB에서 검출한다.
// 평점 축: 실제 리뷰가 그렇듯 J형(고평점 편중). 평균 약 4.06.
//   다만 **장소의 10%는 저평점 장소**로 분포를 뒤집는다 — 점수 공식이 (rating − 3)이라
//   음수 기여 경로가 있고, 그 경로를 걷는 데이터가 없으면 검증이 반쪽이 된다.
//   avg_rating(DECIMAL(3,2))의 분산과 popular_score의 signed 저장도 이 데이터라야 확인된다.
// 유저 축: 전체의 42%만 리뷰를 쓴다(헤비 1% / 미드 9% / 라이트 32%).
//   북마크와 달리 리뷰는 소수가 다수를 쓰는 행동이다.

import { readFileSync } from 'node:fs';

const TSV = process.argv[2];
if (!TSV) {
  process.stderr.write('사용: node generate-review-seed.mjs <place-dist.tsv>\n');
  process.exit(1);
}

const TARGET_REVIEWS = 200_000;
const TOTAL_USERS = 100_000;
const PLACE_ID_BASE = 10_001;
const BATCH = 2_000;          // 리뷰는 content가 있어 북마크(5,000)보다 행이 무겁다
const DAY = 86_400;
const SPAN_DAYS = 548;        // 북마크 시드와 동일한 18개월 창
const TRENDING_R7_RATIO = 0.30;
const LOW_RATED_SHARE = 0.10;
const NOW = Math.floor(Date.now() / 1000);

const out = (s) => process.stdout.write(s + '\n');
const esc = (s) => s.replace(/'/g, "''");
const rand = (n) => Math.floor(Math.random() * n);
const pick = (a) => a[rand(a.length)];
const sqlDt = (e) => new Date(e * 1000).toISOString().slice(0, 19).replace('T', ' ');
const sqlDate = (e) => new Date(e * 1000).toISOString().slice(0, 10);

// ---------- 1) 장소 분포를 DB 실측에서 읽는다 ----------
const places = [];
for (const line of readFileSync(TSV, 'utf8').split('\n')) {
  if (!line.trim()) continue;
  const [id, cnt, r7] = line.split('\t');
  const bookmarks = Number(cnt);
  places.push({
    id: Number(id),
    weight: bookmarks,
    trending: bookmarks > 0 && Number(r7) / bookmarks > TRENDING_R7_RATIO,
  });
}
if (places.length === 0) throw new Error(`${TSV}에서 장소를 하나도 읽지 못했다`);

// 저평점 장소: 결정적으로 고른다. 랜덤이면 재실행 때 대상이 바뀌어
// "지난번과 점수가 다른 이유"를 시드 탓과 코드 탓으로 가를 수 없게 된다.
//
// ⚠️ 다만 `i % 10 === 0` 같은 주기 선택은 쓰면 안 된다 — 실제로 한 번 밟은 함정이다.
// 장소는 leaf당 100개씩 10001부터 연속 배치돼 있고 leaf 내 Zipf 랭크 1위가 각 100의 배수
// 위치에 온다. 10의 배수를 고르면 그 집합이 **모든 leaf의 1위 장소를 통째로 포함**해서
// 북마크 상위 장소만 골라 저평점을 주게 된다 (실측: 리뷰 상위 4곳이 전부 avg 2.3).
// 인기순 검증용 시드가 "인기 있을수록 평점이 낮다"는 없는 상관을 만들어 버린다.
//
// 그래서 id를 섞어 popularity 축과 무관하게 흩는다. 해시라 결정적이고,
// 장소 배치의 어떤 주기와도 정렬되지 않는다.
const mix = (n) => {
  let h = (n * 2654435761) % 4294967296;
  h ^= h >>> 13; h = (h * 1274126177) % 4294967296; h ^= h >>> 16;
  return h >>> 0;
};
places.forEach((p) => { p.lowRated = mix(p.id) % Math.round(1 / LOW_RATED_SHARE) === 0; });

const totalW = places.reduce((a, p) => a + p.weight, 0);
const cum = [];
let acc = 0;
for (const p of places) { acc += p.weight / totalW; cum.push(acc); }
function samplePlace() {
  const u = Math.random();
  let lo = 0, hi = cum.length - 1;
  while (lo < hi) { const mid = (lo + hi) >> 1; if (cum[mid] < u) lo = mid + 1; else hi = mid; }
  return places[lo];
}

// ---------- 2) 평점 · 시각 · 본문 ----------
// 누적 임계값. 고평점 J형 / 저평점 장소는 뒤집는다.
const RATING_HIGH = [[0.45, 5], [0.75, 4], [0.90, 3], [0.96, 2], [1.00, 1]];
const RATING_LOW  = [[0.30, 1], [0.60, 2], [0.80, 3], [0.95, 4], [1.00, 5]];
function sampleRating(lowRated) {
  const u = Math.random();
  for (const [t, r] of (lowRated ? RATING_LOW : RATING_HIGH)) if (u <= t) return r;
  return 3;
}

function sampleCreatedAt(trending) {
  if (trending && Math.random() < 0.45) return NOW - rand(7 * DAY);
  return NOW - Math.floor(SPAN_DAYS * DAY * Math.pow(Math.random(), 1.6));
}

const SLOTS = ['EVENING', 'EVENING', 'AFTERNOON', 'AFTERNOON', 'MORNING']; // 여가 앱 — 저녁·오후 편중
const OPENERS = ['혼자 가기 좋았어요', '분위기가 조용해서 만족', '재방문 의사 있습니다',
  '기대보다 아쉬웠어요', '위치가 접근성 좋네요', '주말엔 사람이 많아요',
  '가격 대비 괜찮습니다', '직원분이 친절했어요', '오래 머물기 편했어요', '다시 갈 것 같진 않아요'];
const CLOSERS = ['1인 좌석이 넉넉합니다.', '조명이 따뜻해서 오래 있었어요.', '소음이 조금 있었습니다.',
  '주차는 어려운 편이에요.', '창가 자리를 추천합니다.', '평일 낮이 한산했어요.',
  '콘센트 자리가 많아요.', '웨이팅이 길었습니다.', '재료가 신선했어요.', '다음엔 다른 메뉴로.'];
function sampleContent(rating) {
  // rating과 문장을 완전히 독립시키면 부자연스럽지만, 이 시드의 목적은 텍스트 품질이 아니라
  // (a) 한글 utf8mb4 왕복 검증 (b) 행 크기의 현실성이다. 길이만 가변으로 준다.
  const n = 1 + rand(3);
  const parts = [`${pick(OPENERS)}(${rating}점).`];
  for (let i = 0; i < n; i++) parts.push(pick(CLOSERS));
  return parts.join(' ');
}

// ---------- 3) 유저별 리뷰 수 ----------
// 헤비 1%(25~35) + 미드 9%(6~10) + 라이트 32%(2~4) ≈ 30,000 + 72,000 + 96,000 = 198,000
function reviewCountFor(rn) {
  if (rn <= TOTAL_USERS * 0.01) return 25 + rand(11);
  if (rn <= TOTAL_USERS * 0.10) return 6 + rand(5);
  if (rn <= TOTAL_USERS * 0.42) return 2 + rand(3);
  return 0;
}

// ---------- 4) 정리 (멱등) ----------
// 자식부터 지운다. place_review_images는 ON DELETE CASCADE지만 place_review_reports는
// 확인된 바 없으므로 명시적으로 지운다 — 벤치 DB에 신고 데이터가 생길 일은 없으나
// "시드가 남긴 것만 시드가 지운다"를 스크립트 스스로 보장하게 둔다.
out(`DELETE ri FROM place_review_images ri
     JOIN place_reviews r ON r.id = ri.place_review_id
     WHERE r.place_id >= ${PLACE_ID_BASE};`);
out(`DELETE rr FROM place_review_reports rr
     JOIN place_reviews r ON r.id = rr.place_review_id
     WHERE r.place_id >= ${PLACE_ID_BASE};`);
out(`DELETE FROM place_reviews WHERE place_id >= ${PLACE_ID_BASE};`);

// ---------- 5) 삽입 ----------
// 북마크 시드와 같은 패턴: rn(행 번호) → 실제 user_id 매핑을 임시 테이블 JOIN으로 푼다.
// users.id를 시드가 직접 가정하지 않기 위함이다.
out(`CREATE TEMPORARY TABLE tmp_bench_users AS
  SELECT id, ROW_NUMBER() OVER (ORDER BY id) AS rn
  FROM users WHERE email LIKE 'bench\\_%@bench.local';`);
out('CREATE INDEX idx_tmp_rn ON tmp_bench_users (rn);');

let batch = [];
let total = 0;
const ratingHist = [0, 0, 0, 0, 0, 0];
function flush() {
  if (batch.length === 0) return;
  out(`INSERT INTO place_reviews (user_id, place_id, visited_at, visit_time_slot, content, rating, created_at, updated_at)
SELECT u.id, v.pid, v.vat, v.slot, v.content, v.rating, v.ts, v.ts
FROM (VALUES\n${batch.join(',\n')}\n) AS v(rn, pid, vat, slot, content, rating, ts)
JOIN tmp_bench_users u ON u.rn = v.rn;`);
  batch = [];
}

outer:
for (let rn = 1; rn <= TOTAL_USERS; rn++) {
  const n = reviewCountFor(rn);
  for (let i = 0; i < n; i++) {
    const p = samplePlace();
    const rating = sampleRating(p.lowRated);
    const ts = sampleCreatedAt(p.trending);
    // 방문일은 작성일보다 앞선다 — visited_at > created_at인 리뷰는 현실에 없다.
    const visitedAt = ts - rand(15) * DAY;
    batch.push(`ROW(${rn}, ${p.id}, '${sqlDate(visitedAt)}', '${pick(SLOTS)}', '${esc(sampleContent(rating))}', ${rating}, '${sqlDt(ts)}')`);
    ratingHist[rating]++;
    total++;
    if (batch.length >= BATCH) flush();
    if (total >= TARGET_REVIEWS) break outer;
  }
}
flush();
out('DROP TEMPORARY TABLE tmp_bench_users;');

// ---------- 6) 검증 출력 ----------
out(`SELECT COUNT(*) AS reviews, MIN(created_at) AS oldest, MAX(created_at) AS newest,
            ROUND(AVG(rating), 3) AS avg_rating
     FROM place_reviews WHERE place_id >= ${PLACE_ID_BASE};`);
out(`SELECT rating, COUNT(*) c FROM place_reviews WHERE place_id >= ${PLACE_ID_BASE}
     GROUP BY rating ORDER BY rating;`);
out(`SELECT place_id, COUNT(*) c, ROUND(AVG(rating),2) avg_r
     FROM place_reviews WHERE place_id >= ${PLACE_ID_BASE}
     GROUP BY place_id ORDER BY c DESC LIMIT 5;`);
out(`SELECT COUNT(*) AS visited_after_created FROM place_reviews
     WHERE place_id >= ${PLACE_ID_BASE} AND visited_at > DATE(created_at);`);
out(`SELECT content FROM place_reviews WHERE place_id >= ${PLACE_ID_BASE} LIMIT 1;`);
out('ANALYZE TABLE place_reviews;');

const trendingCount = places.filter((p) => p.trending).length;
process.stderr.write(
  `[generate] reviews=${total}, 트렌딩 장소=${trendingCount}, 저평점 장소=${places.filter((p) => p.lowRated).length}, ` +
  `평점분포 1~5=${ratingHist.slice(1).join('/')}\n`);
