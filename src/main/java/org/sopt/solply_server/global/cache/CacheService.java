package org.sopt.solply_server.global.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public interface CacheService {

    // == 기본 CRUD == //
    <T> T get(String key, Class<T> clazz);
    <T> T get(String key, Class<T> clazz, Supplier<T> supplier);

    <T> void set(String key, T valueObject);
    <T> void set(String key, T valueObject, int timeout, TimeUnit timeUnit);

    void delete(String key);

    // 배치 삭제
    void deleteBatch(List<String> keys);

    // == 컬렉션 전용 메서드 == //
    <T> List<T> getList(String key, Class<T> elementType);
    <T> List<T> getList(String key, Class<T> elementType, Supplier<List<T>> supplier);

    <T> Set<T> getSet(String key, Class<T> elementType);
    <T> Set<T> getSet(String key, Class<T> elementType, Supplier<Set<T>> supplier);

    // 컬렉션 저장 전용 메서드
    <T> void setList(String key, List<T> list);
    <T> void setList(String key, List<T> list, int timeout, TimeUnit timeUnit);

    <T> void setSet(String key, Set<T> set);
    <T> void setSet(String key, Set<T> set, int timeout, TimeUnit timeUnit);

    // == 추가 기능 == //
    boolean exists(String key);
    long getTtl(String key, TimeUnit timeUnit);
    Set<String> findKeys(String pattern);

    // == 증감 연산 == //
    long increment(String key);
    long increment(String key, long delta);
    long decrement(String key);
    long decrement(String key, long delta);

    // == 캐시 워밍업 == //
    <T> void warmUp(String keyPrefix, Map<String, Supplier<T>> dataSuppliers,
            int timeout, TimeUnit timeUnit);

}