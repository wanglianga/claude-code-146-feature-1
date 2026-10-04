package com.studyroom.it

import com.studyroom.config.DataSeeder
import com.studyroom.support.TimeProvider
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * 集成测试基类：
 * - 自动在 6390 端口启动独立 redis-server（若环境中存在该二进制），并使用 db15 与开发环境隔离；
 * - 每个用例前清空业务表并重新播种（保留真实提交语义）、清 Redis、恢复虚拟时钟。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
abstract class BaseIT {

    @Autowired
    protected lateinit var clock: TimeProvider

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var seeder: DataSeeder

    @BeforeEach
    fun resetEnvironment() {
        // 按外键依赖顺序清空业务数据
        listOf(
            "refund", "charge", "access_log", "complaint", "support_ticket",
            "door_scan_attempt", "store_door_event", "repair_ticket",
            "incident", "timeline_event", "seat_sensor_reading",
            "booking", "membership_plan", "seat", "store", "app_user"
        ).forEach { jdbc.update("DELETE FROM $it") }
        seeder.run()
        jdbc.execute("ALTER TABLE booking ALTER COLUMN id RESTART WITH 1000")
        redisTemplate.execute { connection ->
            connection.serverCommands().flushDb()
            null
        }
        clock.clearOverride()
    }

    @Autowired
    private lateinit var redisTemplate: org.springframework.data.redis.core.StringRedisTemplate

    companion object {
        private const val TEST_PORT = 6390
        private var process: Process? = null

        @BeforeAll
        @JvmStatic
        fun startRedis() {
            if (process != null && process!!.isAlive) return
            val pb = ProcessBuilder(
                "redis-server", "--port", TEST_PORT.toString(),
                "--save", "", "--appendonly", "no"
            ).redirectErrorStream(true)
            process = try {
                pb.start()
            } catch (e: Exception) {
                throw IllegalStateException("集成测试需要 redis-server 可执行文件：${e.message}", e)
            }
            val factory = org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory("localhost", TEST_PORT)
                .apply { database = 15; afterPropertiesSet() }
            val template = org.springframework.data.redis.core.StringRedisTemplate(factory)
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline) {
                try {
                    if (template.connectionFactory!!.connection.ping() == "PONG") return
                } catch (_: Exception) {
                    Thread.sleep(200)
                }
            }
            throw IllegalStateException("测试用 Redis 在 $TEST_PORT 端口启动超时")
        }

        @DynamicPropertySource
        @JvmStatic
        fun redisProps(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.redis.port") { TEST_PORT }
            registry.add("spring.data.redis.database") { 15 }
        }
    }
}
