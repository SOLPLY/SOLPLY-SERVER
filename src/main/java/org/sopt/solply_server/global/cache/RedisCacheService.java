package org.sopt.solply_server.global.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.sopt.solply_server.global.exception.BusinessException;
import org.sopt.solply_server.global.exception.ErrorCode;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Profile("!test")
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisCacheService implements CacheService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper; // TypeReference 지원을 위해 유지

    // == 기본 CRUD == //
    @Override
    public <T> T get(String key, Class<T> clazz) {
        try {
            Object value = redisTemplate.opsForValue().get(key);
            if (value == null) {
                log.debug("캐시 미스 - 키: {}", key);
                return null;
            }
            log.debug("캐시 히트 - 키: {}", key);

            // 타입 변환
            return convertValue(value, clazz);
        } catch (Exception e) {
            log.error("캐시 조회 실패 - 키: {}, 클래스: {}", key, clazz.getSimpleName(), e);
            throw new BusinessException(ErrorCode.REDIS_OPERATION_FAILED);
        }
    }

    @Override
    public <T> T get(String key, Class<T> clazz, Supplier<T> supplier) {
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

    @Override
    public <T> void set(String key, T valueObject) {
        try {
            if (valueObject == null) {
                log.warn("null 값 캐시 시도 - 키: {}", key);
                return;
            }
            redisTemplate.opsForValue().set(key, valueObject);
            log.debug("캐시 저장 완료 - 키: {}", key);
        } catch (Exception e) {
            log.error("캐시 저장 실패 - 키: {}", key, e);
            throw new BusinessException(ErrorCode.REDIS_OPERATION_FAILED);
        }
    }

    @Override
    public <T> void set(String key, T valueObject, int timeout, TimeUnit timeUnit) {
        try {
            if (valueObject == null) {
                log.warn("null 값 캐시 시도 - 키: {}", key);
                return;
            }
            redisTemplate.opsForValue().set(key, valueObject, timeout, timeUnit);
            log.debug("캐시 저장 완료 - 키: {}, TTL: {} {}", key, timeout, timeUnit);
        } catch (Exception e) {
            log.error("캐시 저장 실패(TTL) - 키: {}", key, e);
            throw new BusinessException(ErrorCode.REDIS_OPERATION_FAILED);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Boolean deleted = redisTemplate.delete(key);
            log.debug("캐시 삭제 - 키: {}, 결과: {}", key, deleted);
        } catch (Exception e) {
            log.error("캐시 삭제 작업 실패 - 키: {}", key, e);
            throw new BusinessException(ErrorCode.REDIS_OPERATION_FAILED);
        }
    }

    @Override
    public void deleteBatch(List<String> keys) {

    }


    //== 컬렉션 전용 메서드 ==//
    @Override
    public <T> List<T> getList(String key, Class<T> elementType) {
        try {
            Object value = redisTemplate.opsForValue().get(key);
            if (value == null) {
                log.debug("캐시 미스 - 키: {}", key);
                return null;
            }
            log.debug("캐시 히트 - 키: {}", key);

            // ObjectMapper를 사용하여 List 타입 변환
            return objectMapper.convertValue(value,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, elementType));
        } catch (Exception e) {
            log.error("캐시 List 조회 실패 - 키: {}, 요소타입: {}", key, elementType.getSimpleName(), e);
            throw new BusinessException(ErrorCode.REDIS_OPERATION_FAILED);
        }
    }

    @Override
    public <T> List<T> getList(String key, Class<T> elementType, Supplier<List<T>> supplier) {
        List<T> cachedValue = safeGetList(key, elementType);
        if (cachedValue != null) {
            return cachedValue;
        }

        List<T> value = supplier.get();

        if (value != null) {
            safeSet(key, value);
        }

        return value;
    }

    @Override
    public <T> Set<T> getSet(String key, Class<T> elementType) {
        try {
            Object value = redisTemplate.opsForValue().get(key);
            if (value == null) {
                log.debug("캐시 미스 - 키: {}", key);
                return null;
            }
            log.debug("캐시 히트 - 키: {}", key);

            // ObjectMapper를 사용하여 Set 타입 변환
            return objectMapper.convertValue(value,
                    objectMapper.getTypeFactory().constructCollectionType(Set.class, elementType));
        } catch (Exception e) {
            log.error("캐시 Set 조회 실패 - 키: {}, 요소타입: {}", key, elementType.getSimpleName(), e);
            throw new BusinessException(ErrorCode.REDIS_OPERATION_FAILED);
        }
    }

    @Override
    public <T> Set<T> getSet(String key, Class<T> elementType, Supplier<Set<T>> supplier) {
        Set<T> cachedValue = safeGetSet(key, elementType);
        if (cachedValue != null) {
            return cachedValue;
        }

        Set<T> value = supplier.get();

        if (value != null) {
            safeSet(key, value);
        }

        return value;
    }

    // == 컬렉션 저장 전용 메서드 == //
    @Override
    public <T> void setList(String key, List<T> list) {
        set(key, list);
    }

    @Override
    public <T> void setList(String key, List<T> list, int timeout, TimeUnit timeUnit) {
        set(key, list, timeout, timeUnit);
    }

    @Override
    public <T> void setSet(String key, Set<T> set) {
        set(key, set);
    }

    @Override
    public <T> void setSet(String key, Set<T> set, int timeout, TimeUnit timeUnit) {
        set(key, set, timeout, timeUnit);
    }

    // == 추가 기능 == //
    @Override
    public boolean exists(String key) {
        try {
            return redisTemplate.hasKey(key);
        } catch (Exception e) {
            log.error("캐시 존재 여부 확인 실패 - 키: {}", key, e);
            return false;
        }
    }

    @Override
    public long getTtl(String key, TimeUnit timeUnit) {
        try {
            return redisTemplate.getExpire(key, timeUnit);
        } catch (Exception e) {
            log.error("캐시 TTL 조회 실패 - 키: {}", key, e);
            return -1;
        }
    }

    @Override
    public Set<String> findKeys(String pattern) {
        Set<String> keys = new HashSet<>();
        try {
            redisTemplate.execute((RedisCallback<Void>) connection -> {
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

    // == 증감 연산 == //
    @Override
    public long increment(String key) {
        return increment(key, 1L);
    }

    @Override
    public long increment(String key, long delta) {
        try {
            Long result = redisTemplate.opsForValue().increment(key, delta);
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
            Long result = redisTemplate.opsForValue().decrement(key, delta);
            log.debug("캐시 감소 - 키: {}, 감소량: {}, 결과: {}", key, delta, result);
            return result != null ? result : 0L;
        } catch (Exception e) {
            log.error("캐시 감소 실패 - 키: {}", key, e);
            throw new BusinessException(ErrorCode.REDIS_OPERATION_FAILED);
        }
    }

    // == 캐시 워밍업 == //
    @Override
    public <T> void warmUp(String keyPrefix, Map<String, Supplier<T>> dataSuppliers,
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

    // == Private Helper Methods == //
    private <T> T safeGet(String key, Class<T> clazz) {
        try {
            return get(key, clazz);
        } catch (Exception e) {
            log.warn("캐시 조회 실패 - 키: {}", key, e);
            return null;
        }
    }

    private <T> List<T> safeGetList(String key, Class<T> elementType) {
        try {
            return getList(key, elementType);
        } catch (Exception e) {
            log.warn("캐시 List 조회 실패 - 키: {}", key, e);
            return null;
        }
    }

    private <T> Set<T> safeGetSet(String key, Class<T> elementType) {
        try {
            return getSet(key, elementType);
        } catch (Exception e) {
            log.warn("캐시 Set 조회 실패 - 키: {}", key, e);
            return null;
        }
    }

    private <T> void safeSet(String key, T value) {
        try {
            set(key, value);
            log.debug("캐시 저장 완료 - 키: {}", key);
        } catch (Exception e) {
            log.warn("캐시 저장 실패 - 키: {}", key, e);
        }
    }

    /**
     * 타입 변환 헬퍼 메서드
     */
    private <T> T convertValue(Object value, Class<T> clazz) {
        if (clazz.isInstance(value)) {
            return clazz.cast(value);
        }

        // ObjectMapper를 사용한 타입 변환
        return objectMapper.convertValue(value, clazz);
    }
}