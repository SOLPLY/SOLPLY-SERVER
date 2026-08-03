// bench 사용자 1,000명의 access token을 생성해 data/users.csv로 저장 (#379)
// 사용법: node scripts/generate-users-csv.mjs  (load-test/ 에서)
import jwt from 'jsonwebtoken';
import { execSync } from 'node:child_process';
import { writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

// application.yml jwt.access-secret-key (local 프로파일, 리포에 공개된 개발용 시크릿)
const ACCESS_SECRET_B64 =
  'dGhpcy1pcy1hY2Nlc3Mta2V5LWZvci0zNnRoLXNvcHQtYXBwamFtLXNvbHBseS1hbmQtd2UtYXJlLXNvbHBseS1zZXJ2ZXI=';
const SECRET = Buffer.from(ACCESS_SECRET_B64, 'base64');
const PLATFORM = 'KAKAO'; // SocialPlatform.name() — enum 상수명

const ids = execSync(
  `docker exec solply-bench-mysql mysql -N -usolplyuser -psolplyuserpwd solply_bench_db ` +
  `-e "SELECT id FROM users WHERE email LIKE 'bench\\_%@bench.local' ORDER BY id LIMIT 1000"`,
  { encoding: 'utf8' },
).trim().split('\n');

if (ids.length !== 1000) throw new Error(`expected 1000 ids, got ${ids.length} — 시드 먼저 실행`);

const tokens = ids.map((id) =>
  jwt.sign({ type: 'access', platform: PLATFORM }, SECRET, {
    algorithm: 'HS512',
    subject: String(id).trim(),
    expiresIn: '7d',
  }),
);

const out = join(dirname(fileURLToPath(import.meta.url)), '..', 'data', 'users.csv');
writeFileSync(out, 'token\n' + tokens.join('\n') + '\n');
console.log(`wrote ${tokens.length} tokens -> ${out}`);
