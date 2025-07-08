package org.sopt.solply_server.global.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.Serializable;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RedisCacheService implements CacheService {

    private final StringRedisTemplate springRedisTemplate;
    private final ObjectMapper objectMapper;

    // == 기본 CRUD == //
    @Override
    public <T extends Serializable> T get(String key, Class<T> clazz) {
        try {
            String json = springRedisTemplate.opsForValue().get(key);
            if (json == null) {
                log.debug("캐시 미스 - 키: {}", key);
                return null;
            }
            log.debug("캐시 히트 - 키: {}", key);
            return objectMapper.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            log.error("캐시 역직렬화 실패 - 키: {}, 클래스: {}", key, clazz.getSimpleName(), e);
            throw new BusinessException(ErrorCode.REDIS_SERIALIZATION_FAILED);
        } catch (Exception e) {
            log.error("캐시 조회 실패 - 키: {}", key, e);
            throw new BusinessException(ErrorCode.REDIS_OPERATION_FAILED);
        }
    }

    @Override
    public <T extends Serializable> T get(String key, Class<T> clazz, Supplier<T> supplier) {
        // 캐시 조회
        T cachedValue = safeGet(key, clazz);
        if (cachedValue != null) {
            return cachedValue;
        }

        // DB 조회
        T value = supplier.get();

        // 캐시 저장 (실패해도 무시)
        if (value != null) {
            safeSet(key, value);
        }

        return value;
    }

    private <T extends Serializable> T safeGet(String key, Class<T> clazz) {
        try {
            return get(key, clazz);
        } catch (Exception e) {
            log.warn("캐시 조회 실패 - 키: {}", key, e);
            return null;
        }
    }

    private <T extends Serializable> void safeSet(String key, T value) {
        try {
            set(key, value);
            log.debug("캐시 저장 완료 - 키: {}", key);
        } catch (Exception e) {
            log.warn("캐시 저장 실패 - 키: {}", key, e);
        }
    }

    @Override
    public <T extends Serializable> void set(String key, T valueObject) {
        try {
            if (valueObject == null) {
                log.warn("null 값 캐시 시도 - 키: {}", key);
                return;
            }
            String json = objectMapper.writeValueAsString(valueObject);
            springRedisTemplate.opsForValue().set(key, json);
            log.debug("캐시 저장 완료 - 키: {}", key);
        } catch (JsonProcessingException e) {
            log.error("캐시 직렬화 실패 - 키: {}", key, e);
            throw new BusinessException(ErrorCode.REDIS_SERIALIZATION_FAILED);
        } catch (Exception e) {
            log.error("캐시 저장 실패 - 키: {}", key, e);
            throw new BusinessException(ErrorCode.REDIS_OPERATION_FAILED);
        }
    }

    @Override
    public <T extends Serializable> void set(String key, T valueObject, int timeout, TimeUnit timeUnit) {
        try {
            if (valueObject == null) {
                log.warn("null 값 캐시 시도 - 키: {}", key);
                return;
            }
            String json = objectMapper.writeValueAsString(valueObject);
            springRedisTemplate.opsForValue().set(key, json, timeout, timeUnit);
            log.debug("캐시 저장 완료 - 키: {}, TTL: {} {}", key, timeout, timeUnit);
        } catch (JsonProcessingException e) {
            log.error("캐시 직렬화 실패 - 키: {}", key, e);
            throw new BusinessException(ErrorCode.REDIS_SERIALIZATION_FAILED);
        } catch (Exception e) {
            log.error("캐시 저장 실패(TTL) - 키: {}", key, e);
            throw new BusinessException(ErrorCode.REDIS_OPERATION_FAILED);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Boolean deleted = springRedisTemplate.delete(key);
            log.debug("캐시 삭제 - 키: {}, 결과: {}", key, deleted);
        } catch (Exception e) {
            log.error("캐시 삭제 작업 실패 - 키: {}", key, e);
            throw new BusinessException(ErrorCode.REDIS_OPERATION_FAILED);
        }
    }

    //== 추가 기능 ==//
    @Override
    public boolean exists(String key) {
        try {
            return springRedisTemplate.hasKey(key);
        } catch (Exception e) {
            log.error("캐시 존재 여부 확인 실패 - 키: {}", key, e);
            return false;
        }
    }

    @Override
    public long getTtl(String key, TimeUnit timeUnit) {
        try {
            return springRedisTemplate.getExpire(key, timeUnit);
        } catch (Exception e) {
            log.error("캐시 TTL 조회 실패 - 키: {}", key, e);
            return -1;
        }
    }

    @Override
    public Set<String> findKeys(String pattern) {
        Set<String> keys = new HashSet<>();
        try {
            springRedisTemplate.execute((RedisCallback<Void>) connection -> {
                try (Cursor<byte[]> cursor = connection.scan(ScanOptions.scanOptions()
                        .match(pattern)
                        .count(1000)
                        .build())) {

                    while (cursor.hasNext()) {
                        keys.add(new String(cursor.next()));
                    }
                } catch (Exception e) {
                    log.error("Redis 스캔 작업 실패 - 패턴: {}", pattern, e);
                    throw new BusinessException(ErrorCode.REDIS_OPERATION_FAILED);
                }
                return null;
            });

            log.debug("패턴 '{}' 에 대해 {}개의 키를 찾음", pattern, keys.size());
            return keys;
        } catch (Exception e) {
            log.error("Redis findKeys 작업 실패 - 패턴: {}", pattern, e);
            throw new BusinessException(ErrorCode.REDIS_OPERATION_FAILED);
        }
    }

    /**
     * 증감 연산
     */
    @Override
    public long increment(String key) {
        return increment(key, 1L);
    }


    @Override
    public long increment(String key, long delta) {
        try {
            Long result = springRedisTemplate.opsForValue().increment(key, delta);
            log.debug("캐시 증가 - 키: {}, 증가량: {}, 결과: {}", key, delta, result);
            return result != null ? result : 0L;
        } catch (Exception e) {
            log.error("캐시 증가 실패 - 키: {}", key, e);
            throw new BusinessException(ErrorCode.REDIS_OPERATION_FAILED);
        }
    }

    @Override
    public long decrement(String key) {
        return decrement(key, 1L);
    }

    @Override
    public long decrement(String key, long delta) {
        try {
            Long result = springRedisTemplate.opsForValue().decrement(key, delta);
            log.debug("캐시 감소 - 키: {}, 감소량: {}, 결과: {}", key, delta, result);
            return result != null ? result : 0L;
        } catch (Exception e) {
            log.error("캐시 감소 실패 - 키: {}", key, e);
            throw new BusinessException(ErrorCode.REDIS_OPERATION_FAILED);
        }
    }

    /**
     * 캐시에 미리 데이터를 적재
     */
    @Override
    public <T extends Serializable> void warmUp(String keyPrefix, Map<String, Supplier<T>> dataSuppliers,
            int timeout, TimeUnit timeUnit) {
        log.info("캐시 워밍업 시작 - prefix: {}", keyPrefix);

        dataSuppliers.forEach((key, supplier) -> {
            try {
                String fullKey = keyPrefix + ":" + key;
                if (!exists(fullKey)) {
                    T data = supplier.get();
                    if (data != null) {
                        set(fullKey, data, timeout, timeUnit);
                        log.debug("캐시 워밍업 완료 - 키: {}", fullKey);
                    }
                }
            } catch (Exception e) {
                log.warn("캐시 워밍업 실패 - 키: {}", key, e);
            }
        });

        log.info("캐시 워밍업 완료 - prefix: {}", keyPrefix);
    }

    /**
     * TODO: 배치 작업
     */

}