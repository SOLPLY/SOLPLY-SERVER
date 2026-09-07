# GPT 플랜 리뷰

현재 Claude가 수립한 플랜을 **OpenAI 추론 모델(o5-high/o3-mini)**에 전송하여 논리적 허점과 구조적 리스크를 심층 검토받고, 
한국어로 요약해 반환한다.

## 사용법
```
/review-plan
/review-plan <플랜 내용을 직접 붙여넣기>
```

## 절차

1. 플랜 추출 — 인자로 받은 텍스트 또는 컨텍스트 내 최신 플랜을 가져온다.
2. **민감정보 마스킹** — 아래 마스킹 규칙을 적용한 뒤 전송한다.
3. Reasoning API 호출 — 아래 curl 규격에 맞춰 호출한다. (temperature 제외)
4. JSON 파싱 및 번역 — 응답받은 영문 전문 용어들을 한국어로 매끄럽게 다듬는다.

## 민감정보 마스킹 규칙

API 전송 전 플랜 텍스트에서 아래 항목을 감지하여 치환한다.

| 항목 | 예시 | 치환값 |
|------|------|--------|
| API 키 / 시크릿 | `sk-abc123...` | `[REDACTED_API_KEY]` |
| JWT / 토큰 | `eyJhbGci...` | `[REDACTED_TOKEN]` |
| DB 접속 정보 | `jdbc:mysql://user:pass@host` | `[REDACTED_DB_URL]` |
| IP 주소 / 내부 호스트 | `192.168.x.x`, `internal.host` | `[REDACTED_HOST]` |
| 개인정보 (이름·이메일·전화번호) | `홍길동`, `user@company.com` | `[REDACTED_PII]` |
| 환경 변수 값 | `DB_PASSWORD=secret` | `DB_PASSWORD=[REDACTED]` |

마스킹 후 사용자에게 치환된 항목 목록을 보여주고 전송 여부를 확인받는다.

```
마스킹된 항목: [REDACTED_API_KEY] 1건, [REDACTED_DB_URL] 1건
위 내용으로 GPT에 전송할까요? (y/n)
```

## API 호출

```bash
curl https://api.openai.com/v1/chat/completions \
  -s \
  -H "Authorization: Bearer $OPENAI_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "o5-high",
    "messages": [
      {
        "role": "developer",
        "content": "You are a critical software architect. Review the implementation plan for logic flaws, race conditions, and scalability. Respond ONLY in valid JSON with keys: \"risks\", \"improvements\", \"missing\". Be extremely critical."
      },
      {
        "role": "user",
        "content": "<PLAN>"
      }
    ],
    "response_format": { "type": "json_object" },
    "reasoning_effort": "high"
  }'
```

`<PLAN>` 자리에 실제 플랜 내용을 삽입한다.

## 출력 형식

GPT 응답을 파싱하여 아래 형식으로 사용자에게 제시한다.

```markdown
## 🧠 GPT 추론 모델 플랜 리뷰 결과

> **모델 의견:** (추론 모델이 파악한 전체적인 설계 완성도 한 줄 평)

### ⚠️ 기술적 리스크
- (논리적 모순, 성능 저하 가능성 등)

### 💡 아키텍처 개선 제안
- (더 효율적인 라이브러리나 패턴 추천)

### 🔍 고려되지 않은 케이스
- (예외 상황, 보안 취약점, 누락된 환경 변수 등)
```

## 주의사항
- OPENAI_API_KEY 환경 변수가 설정되어 있어야 한다.
- Reasoning 모델 특성: temperature, top_p 등을 넣으면 에러가 발생하므로 제외했습니다.
- Developer Role: 최신 모델 가이드에 따라 system 대신 developer 롤을 사용하여 모델의 추론 방향성을 제어합니다. 
- 속도: 추론 모델은 '생각하는 시간'이 필요하므로 응답에 수 초~십수 초가 소요될 수 있음을 사용자에게 알립니다.
