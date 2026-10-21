package dev.portfolio.aftersales;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Service
class PolicyService {
    private static final String CACHE_KEY = "aftersales:policies:v1";
    private final Store store;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    PolicyService(Store store, StringRedisTemplate redis, ObjectMapper mapper) {
        this.store = store;
        this.redis = redis;
        this.mapper = mapper;
    }

    List<PolicyRecord> activePolicies() {
        try {
            String cached = redis.opsForValue().get(CACHE_KEY);
            if (cached != null) return mapper.readValue(cached, new TypeReference<>() {});
        } catch (Exception ignored) {
            // MySQL remains the source of truth when Redis is unavailable.
        }
        List<PolicyRecord> policies = store.policies().stream().filter(PolicyRecord::active).toList();
        try {
            redis.opsForValue().set(CACHE_KEY, mapper.writeValueAsString(policies), Duration.ofMinutes(5));
        } catch (Exception ignored) {
            // Cache availability must not block case processing.
        }
        return policies;
    }

    PolicyRecord update(long id, PolicyUpdateRequest request) {
        store.policy(id).orElseThrow(() -> new ApiException(org.springframework.http.HttpStatus.NOT_FOUND, "规则不存在"));
        store.updatePolicy(id, request.content(), request.active());
        try { redis.delete(CACHE_KEY); } catch (Exception ignored) {}
        return store.policy(id).orElseThrow();
    }
}
