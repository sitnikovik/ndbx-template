package com.ndbx.lab2.service

import com.ndbx.lab2.config.AppSessionProperties
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Service
class SessionService(
    private val redisTemplate: StringRedisTemplate,
    private val sessionProperties: AppSessionProperties
) {
    companion object {
        private const val SESSION_PREFIX = "sid:"
        private const val FIELD_CREATED_AT = "created_at"
        private const val FIELD_UPDATED_AT = "updated_at"
        private val SID_PATTERN = Regex("^[0-9a-f]{32}$")
        private val SECURE_RANDOM = SecureRandom()

        private val CREATE_SESSION_SCRIPT: RedisScript<Long> = RedisScript.of(
            """
            if redis.call('EXISTS', KEYS[1]) == 0 then
              redis.call('HSET', KEYS[1], 'created_at', ARGV[1], 'updated_at', ARGV[1])
              redis.call('EXPIRE', KEYS[1], ARGV[2])
              return 1
            end
            return 0
            """.trimIndent(),
            Long::class.java
        )
    }

    fun generateSid(): String {
        val bytes = ByteArray(16)
        SECURE_RANDOM.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun isValidSid(sid: String): Boolean = SID_PATTERN.matches(sid)

    fun sessionExists(sid: String): Boolean =
        redisTemplate.hasKey("$SESSION_PREFIX$sid") == true

    fun createSession(sid: String): Boolean {
        val now = nowRfc3339()
        val result = redisTemplate.execute(
            CREATE_SESSION_SCRIPT,
            listOf("$SESSION_PREFIX$sid"),
            now,
            sessionProperties.ttl.toString()
        )
        return result == 1L
    }

    fun createUniqueSession(): String {
        repeat(5) {
            val sid = generateSid()
            if (createSession(sid)) return sid
        }
        error("Failed to generate a unique session id after retries")
    }

    fun updateSession(sid: String) {
        val key = "$SESSION_PREFIX$sid"
        redisTemplate.opsForHash<String, String>().put(key, FIELD_UPDATED_AT, nowRfc3339())
        redisTemplate.expire(key, Duration.ofSeconds(sessionProperties.ttl))
    }

    fun getTtl(): Long = sessionProperties.ttl

    private fun nowRfc3339(): String =
        Instant.now().atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}
