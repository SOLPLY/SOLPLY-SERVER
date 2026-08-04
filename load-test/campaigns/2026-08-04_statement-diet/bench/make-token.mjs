// 특정 user id의 access token 1개를 발급한다 — 정합성 게이트(ⓓ) 전용.
//
// 왜 users.csv를 쓰지 않는가: csv는 토큰만 담고 어느 유저의 것인지 기록하지 않는다.
// 게이트는 (1) A/B 양쪽에서 **같은 유저**로 쏴야 isBookmarked 같은 유저 의존 필드가
// 고정되고, (2) 소프트 삭제 대상 유저의 토큰이 따로 필요하다. 그래서 id를 지정해 발급한다.
// 서명 방식은 shared/generate-users-csv.mjs와 동일하다 (HS512, subject=id, type/platform 클레임).
//
// 사용: node make-token.mjs <userId>
import jwt from 'jsonwebtoken';

const ACCESS_SECRET_B64 =
  'dGhpcy1pcy1hY2Nlc3Mta2V5LWZvci0zNnRoLXNvcHQtYXBwamFtLXNvbHBseS1hbmQtd2UtYXJlLXNvbHBseS1zZXJ2ZXI=';
const SECRET = Buffer.from(ACCESS_SECRET_B64, 'base64');
const PLATFORM = 'KAKAO';

const id = process.argv[2];
if (!id) throw new Error('usage: node make-token.mjs <userId>');

process.stdout.write(
  jwt.sign({ type: 'access', platform: PLATFORM }, SECRET, {
    algorithm: 'HS512',
    subject: String(id).trim(),
    expiresIn: '7d',
  }),
);
