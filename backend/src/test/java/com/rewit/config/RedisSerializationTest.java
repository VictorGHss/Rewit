package com.rewit.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.io.Serializable;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Testes de Serialização Redis com GenericJacksonJsonRedisSerializer (Step 4.1.1)")
class RedisSerializationTest {

    record SamplePayload(String id, String message, int count) implements Serializable {}

    @Test
    @DisplayName("Deve serializar e deserializar objetos complexos e mapas via GenericJacksonJsonRedisSerializer")
    void shouldSerializeAndDeserializeJsonPayload() {
        GenericJacksonJsonRedisSerializer serializer = GenericJacksonJsonRedisSerializer.builder().build();

        SamplePayload original = new SamplePayload("payload-123", "Rewit Cache Test", 42);
        byte[] bytes = serializer.serialize(original);

        assertNotNull(bytes);
        assertTrue(bytes.length > 0);

        Object deserialized = serializer.deserialize(bytes);
        assertNotNull(deserialized);
        // Sem default typing inseguro, o objeto é deserializado como Map ou tipo compatível
        if (deserialized instanceof Map<?, ?> map) {
            assertEquals("payload-123", map.get("id"));
            assertEquals("Rewit Cache Test", map.get("message"));
            assertEquals(42, map.get("count"));
        } else if (deserialized instanceof SamplePayload payload) {
            assertEquals("payload-123", payload.id());
            assertEquals(42, payload.count());
        } else {
            fail("Tipo inesperado após deserialização: " + deserialized.getClass());
        }
    }

    @Test
    @DisplayName("Deve serializar e deserializar valores nulos com segurança")
    void shouldHandleNullValuesSafely() {
        GenericJacksonJsonRedisSerializer serializer = GenericJacksonJsonRedisSerializer.builder().build();

        byte[] nullBytes = serializer.serialize(null);
        assertNotNull(nullBytes);
        assertEquals(0, nullBytes.length);

        Object deserializedNull = serializer.deserialize(nullBytes);
        assertNull(deserializedNull);
    }

    @Test
    @DisplayName("RedisConfig deve instanciar RedisTemplate devidamente configurado com novo serializer")
    void shouldConfigureRedisTemplateProperly() {
        RedisConfig config = new RedisConfig();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration("localhost", 6379));
        factory.afterPropertiesSet();

        RedisTemplate<String, Object> template = config.redisTemplate(factory);

        assertNotNull(template);
        assertInstanceOf(StringRedisSerializer.class, template.getKeySerializer());
        assertInstanceOf(GenericJacksonJsonRedisSerializer.class, template.getValueSerializer());
        assertInstanceOf(StringRedisSerializer.class, template.getHashKeySerializer());
        assertInstanceOf(GenericJacksonJsonRedisSerializer.class, template.getHashValueSerializer());

        factory.destroy();
    }
}
