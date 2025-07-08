package org.sopt.solply_server.global.cache;

import java.io.Serializable;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public interface CacheService {

    /**
     * 캐시에서 값 조회(T: 직렬화 가능한 타입)
      */
    <T extends Serializable> T get(String key, Class<T> clazz);

    /**
     * 캐시 미스 시, Supplier가 호출되어 DB나 다른 소스에서 값을 가져오는 로직을 수행
     * -> Lazy Evaluation 방식
     */
    <T extends Serializable> T get(String key, Class<T> clazz, Supplier<T> supplier);

    /**
     * 캐시에 값 저장
     */
    <T extends Serializable> void set(String key, T valueObject);

    /**
     * TTL을 설정하고 값 저장
     */
    <T extends Serializable> void set(String key, T valueObject, int timeout, TimeUnit timeUnit);

    /**
     * 캐시에서 값 삭제
     */
    void delete(String key);

    /**
     * 키 존재 여부 확인
     */
    boolean exists(String key);

    /**
     * TTL 조회
     */
    long getTtl(String key, TimeUnit timeUnit);

    /**
     * 패턴으로 키 검색
     */
    Set<String> findKeys(String pattern);

    /**
     * 원자적 증가 연산
     */
    long increment(String key);
    long increment(String key, long delta);

    /**
     * 원자적 감소 연산
     */
    long decrement(String key);
    long decrement(String key, long delta);

    /**
     * 캐시에 미리 데이터를 적재
     */
    <T extends Serializable> void warmUp(String keyPrefix, Map<String, Supplier<T>> dataSuppliers,
            int timeout, TimeUnit timeUnit);

    /**
     * 배치 조회 / 저장(TTL X)
     */
//    <T extends Serializable> Map<String, T> multiGet(Set<String> keys, Class<T> clazz);
//    <T extends Serializable> void multiSet(Map<String, T> keyValueMap);

    /**
     * 배치 조회 / 저장(TTL O)
     */
//    <T extends Serializable> Map<String, T> multiGet(Set<String> keys, Class<T> clazz, int timeout, TimeUnit timeUnit);
//    <T extends Serializable> void multiSet(Map<String, T> keyValueMap, int timeout, TimeUnit timeUnit);



}
