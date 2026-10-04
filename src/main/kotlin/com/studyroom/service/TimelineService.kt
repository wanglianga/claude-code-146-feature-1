package com.studyroom.service

import com.studyroom.domain.TimelineEvent
import com.studyroom.repo.TimelineEventRepository
import com.studyroom.support.TimeProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class TimelineService(
    private val repo: TimelineEventRepository,
    private val clock: TimeProvider
) {
    @Transactional
    fun record(eventType: String, storeId: Long? = null, bookingNo: String? = null, actor: String? = null, detail: String? = null) {
        repo.save(
            TimelineEvent(
                eventType = eventType,
                occurredAt = clock.now(),
                storeId = storeId,
                bookingNo = bookingNo,
                actor = actor,
                detail = detail
            )
        )
    }
}

/** 业务编号生成（不依赖 DB 自增即可对外引用） */
object BizNoGenerator {
    private fun prefix(p: String, n: Long): String {
        val ts = java.time.Instant.now().toEpochMilli()
        return "$p$ts${n.toString().padStart(4, '0')}"
    }
    fun booking(id: Long) = prefix("BK", id)
    fun refund(id: Long) = prefix("RF", id)
    fun ticket(id: Long) = prefix("TK", id)
    fun complaint(id: Long) = prefix("CP", id)
    fun incident(id: Long) = prefix("IN", id)
    fun doorAttempt(id: Long) = prefix("DA", id)
    fun doorEvent(id: Long) = prefix("DE", id)
    fun repair(id: Long) = prefix("RP", id)
    fun monitor(seed: String): String = "MON-" + seed.uppercase().replace(Regex("[^A-Z0-9]"), "").takeLast(10)
}
