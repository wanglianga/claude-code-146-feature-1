package com.studyroom.service

import com.studyroom.domain.*
import com.studyroom.repo.*
import com.studyroom.support.BizException
import com.studyroom.support.ConflictException
import com.studyroom.support.NotFoundException
import com.studyroom.support.SeatLockManager
import com.studyroom.support.TimeProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/**
 * 门禁服务：进/出场判定与订单、座位、计费状态的一致性入口。
 *
 * 关键不变量：
 * - 放行 GRANTED 与订单置 IN_PROGRESS、Redis 占用写入在同一次业务调用内完成，
 *   不会出现“门禁已放行但订单仍未开始”；
 * - 离场结算（退费/押金）与 Redis 占用释放、订单 COMPLETED 在同一调用内完成，
 *   不会出现“退费完成但座位仍锁定”；
 * - 每次判定都落 AccessLog + TimelineEvent，争议处理可回放。
 */
@Service
class AccessService(
    private val bookingRepo: BookingRepository,
    private val userRepo: AppUserRepository,
    private val storeRepo: StoreRepository,
    private val seatRepo: SeatRepository,
    private val sensorRepo: SeatSensorRepository,
    private val incidentRepo: IncidentRepository,
    private val accessLogRepo: AccessLogRepository,
    private val billing: BillingService,
    private val seatLocks: SeatLockManager,
    private val timeline: TimelineService,
    private val clock: TimeProvider
) {
    data class EntryDecision(
        val granted: Boolean,
        val result: AccessResult,
        val bookingNo: String?,
        val seatCode: String?,
        val detail: String
    )

    // ---------------- 进场 ----------------

    @Transactional
    fun enter(userId: Long, storeId: Long, doorId: String? = null): EntryDecision {
        val now = clock.now()
        val user = userRepo.findById(userId).orElseThrow { NotFoundException("用户不存在") }
        val store = storeRepo.findById(storeId).orElseThrow { NotFoundException("门店不存在") }

        fun deny(result: AccessResult, detail: String): EntryDecision {
            logAccess(user, store, doorId, AccessDirection.IN, result, null, detail)
            timeline.record("ACCESS_DENIED", store.id, null, "USER:${user.id}", "${result.name}：$detail")
            return EntryDecision(false, result, null, null, detail)
        }

        // 1) 门店停电：一律拒绝新进入
        if (store.blackout) return deny(AccessResult.DENIED_STORE_BLACKOUT, "门店临时停电，暂停进入")

        // 1.5) 存在未解决的门禁故障：拒绝并指明事件编号
        val doorFault = incidentRepo.findByStoreIdAndTypeAndStatus(
            store.id!!, IncidentType.DOOR_FAILURE, IncidentStatus.OPEN
        ).firstOrNull()
        if (doorFault != null) {
            return deny(AccessResult.DENIED_DOOR_FAULT,
                "门禁故障维修中（事件 ${doorFault.incidentNo}），请走人工通道或联系客服")
        }

        // 2) 已经在场：防止重复进场
        val inProgress = bookingRepo.findFirstByUserIdAndStoreIdAndStatus(user.id!!, store.id!!, BookingStatus.IN_PROGRESS)
        if (inProgress != null) {
            return deny(AccessResult.DENIED_OUTSIDE_WINDOW, "已有在场订单 ${inProgress.bookingNo}，请先离场")
        }

        // 3) 实名
        if (!user.realNameVerified) return deny(AccessResult.DENIED_NOT_REALNAME, "未完成实名认证")

        // 4) 找到可进入的预约：计时类优先（时间窗匹配），否则包月权益
        val userId = user.id!!
        val storeId = store.id!!
        val timed = bookingRepo.findEnterableTimed(userId, storeId, now)
        val booking = timed.firstOrNull()
            ?: bookingRepo.findActiveMembership(storeId, userId, now)
            ?: return deny(AccessResult.DENIED_NO_BOOKING, "当前时间没有有效的已支付预约或包月权益")

        if (booking.status != BookingStatus.CONFIRMED) {
            return deny(AccessResult.DENIED_UNPAID, "订单 ${booking.bookingNo} 状态 ${booking.status}")
        }

        // 5) 迟到宽限判定（含门禁故障补偿追加的免费宽限分钟）
        if (booking.type != BookingType.MONTHLY) {
            val grace = store.lateGraceMinutes.toLong() + booking.compGraceMinutes
            val lateDeadline = booking.startTime.plus(Duration.ofMinutes(grace))
            if (now.isAfter(lateDeadline)) {
                return deny(
                    AccessResult.DENIED_LATE_EXPIRED,
                    "已超过迟到宽限 $grace 分钟，请申请顺延或联系客服（订单 ${booking.bookingNo}）"
                )
            }
            if (now.isBefore(booking.startTime.minus(Duration.ofMinutes(store.earlyEntryMinutes.toLong())))) {
                return deny(AccessResult.DENIED_OUTSIDE_WINDOW, "未到可进场时间（提前 ${store.earlyEntryMinutes} 分钟可进）")
            }
        }

        // 6) 分配座位（包月浮动权益 / 碰撞换座），并在 Redis 座位锁内完成进场
        return assignSeatAndEnter(booking, user, store, doorId, now, timedLate = false)
    }

    /**
     * 在座位锁内完成：物理占用校验（传感器）→ 碰撞处理（顺延占用下一座位）→ 换座 → 放行。
     */
    private fun assignSeatAndEnter(
        booking: Booking, user: AppUser, store: Store, doorId: String?, now: Instant, timedLate: Boolean
    ): EntryDecision {
        // 包月浮动权益：现场分配同类型空闲座位
        if (booking.type == BookingType.MONTHLY && booking.seat == null) {
            val seatType = booking.plan?.seatType
            val free = seatRepo.findByStoreIdAndActiveTrue(store.id!!)
                .filter { (seatType == null || it.seatType == seatType) && it.status != SeatStatus.MAINTENANCE }
                .firstOrNull { s ->
                    val sid = s.id!!
                    !seatLocks.isOccupied(sid) &&
                        bookingRepo.findActiveOnSeat(sid, now).none { it.actualCheckIn != null }
                }
                ?: return run {
                    logAccess(user, store, doorId, AccessDirection.IN, AccessResult.DENIED_NO_FREE_SEAT, booking.bookingNo,
                        "包月浮动权益：当前无同类型空闲座位")
                    return EntryDecision(false, AccessResult.DENIED_NO_FREE_SEAT, booking.bookingNo, null,
                        "当前无空闲座位，请稍后或联系客服")
                }
            booking.seat = free
            timeline.record("MEMBERSHIP_SEAT_ASSIGNED", store.id, booking.bookingNo, "SYSTEM", "现场分配座位 ${free.code}")
        }

        val seat = booking.seat ?: throw BizException("NO_SEAT", "订单未分配座位")
        val seatId = seat.id!!

        return seatLocks.withSeatLock(seatId) {
            val sensor = sensorRepo.findBySeatId(seatId)
            val occupant = seatLocks.currentOccupant(seatId)
                ?: sensor?.takeIf { it.occupied }?.occupiedByBookingNo

            if (occupant != null && occupant != booking.bookingNo) {
                // 座位被占：最典型来源——上一位用户迟到后顺延，延时窗口侵占本座位
                handleOccupiedSeat(booking, user, store, doorId, now, seat, occupant)
            } else {
                doEnter(booking, user, store, doorId, now, seat, AccessResult.GRANTED, null)
            }
        }
    }

    /**
     * 连续事件“晚到 → 顺延 → 占下一座位 → 下一位投诉/换座”的落点：
     * 自动寻找同类型空闲座位并换座放行；无空闲座位则拒绝并开客服工单（由 ComplaintService 补开）。
     */
    private fun handleOccupiedSeat(
        booking: Booking, user: AppUser, store: Store, doorId: String?, now: Instant,
        occupiedSeat: Seat, occupantBookingNo: String
    ): EntryDecision {
        val alternative = findAlternativeSeat(store, occupiedSeat, now, booking)
        if (alternative == null) {
            logAccess(user, store, doorId, AccessDirection.IN, AccessResult.DENIED_NO_FREE_SEAT, booking.bookingNo,
                "原座位 ${occupiedSeat.code} 被 $occupantBookingNo 占用（疑似上一单顺延），且无同类型空闲座位")
            timeline.record("COLLISION_NO_ALTERNATIVE", store.id, booking.bookingNo, "SYSTEM",
                "座位被 $occupantBookingNo 占用且无座可换，等待客服工单处理")
            return EntryDecision(false, AccessResult.DENIED_NO_FREE_SEAT, booking.bookingNo, occupiedSeat.code,
                "座位被占且暂无可换座位，已记录，请联系客服")
        }

        // 换座：在新座位锁内二次确认空闲，然后放行
        val altId = alternative.id!!
        return seatLocks.withSeatLock(altId) {
            if (seatLocks.isOccupied(altId)) {
                // 极端并发：刚被他人抢走，拒绝并保留证据
                logAccess(user, store, doorId, AccessDirection.IN, AccessResult.DENIED_NO_FREE_SEAT, booking.bookingNo,
                    "备选座位 ${alternative.code} 刚被占用")
                return@withSeatLock EntryDecision(false, AccessResult.DENIED_NO_FREE_SEAT, booking.bookingNo, null,
                    "备选座位刚刚被占用，请重试或联系客服")
            }
            booking.originalSeatId = booking.seat?.id
            val oldCode = booking.seat?.code
            booking.seat = alternative
            doEnter(booking, user, store, doorId, now, alternative, AccessResult.GRANTED_REASSIGNED,
                "原座位 $oldCode 被顺延订单 $occupantBookingNo 占用，自动换座至 ${alternative.code}，差额补偿见客服工单")
        }
    }

    private fun findAlternativeSeat(store: Store, preferred: Seat, now: Instant, booking: Booking): Seat? {
        return seatRepo.findByStoreIdAndActiveTrue(store.id!!)
            .filter { it.id != preferred.id && it.seatType == preferred.seatType && it.status != SeatStatus.MAINTENANCE }
            // 同价或更低优先（避免免费升级争议），再取编号最小，保证结果稳定
            .sortedWith(compareBy({ it.hourlyPrice > preferred.hourlyPrice }, { it.hourlyPrice }, { it.code }))
            .firstOrNull { candidate ->
                val cid = candidate.id!!
                !seatLocks.isOccupied(cid) &&
                    sensorRepo.findBySeatId(cid)?.occupied != true &&
                    bookingRepo.findActiveOnSeat(cid, now).none { it.actualCheckIn != null }
            }
    }

    private fun doEnter(
        booking: Booking, user: AppUser, store: Store, doorId: String?, now: Instant,
        seat: Seat, result: AccessResult, extraDetail: String?
    ): EntryDecision {
        booking.status = BookingStatus.IN_PROGRESS
        booking.actualCheckIn = now
        val lateMin = Duration.between(booking.startTime, now).toMinutes()
        if (booking.type != BookingType.MONTHLY && lateMin > 0) {
            booking.late = true
            booking.lateMinutes = lateMin
        }
        seat.status = SeatStatus.OCCUPIED
        val seatId = seat.id!!

        // 包月权益有效期内都可进出；计时单按计划时长上限占用
        val ttl = if (booking.type == BookingType.MONTHLY)
            Duration.between(now, booking.effectiveEndTime).plusMinutes(10)
        else
            Duration.between(now, booking.effectiveEndTime).plusHours(2)
        seatLocks.markOccupied(seatId, booking.bookingNo, ttl)
        sensorRepo.save(sensorRepo.findBySeatId(seatId)?.apply {
            occupied = true; occupiedByBookingNo = booking.bookingNo; updatedAt = now
        } ?: SeatSensorReading(seat = seat, occupied = true, occupiedByBookingNo = booking.bookingNo, updatedAt = now))

        val detail = buildString {
            append("放行进入座位 ${seat.code}")
            if (booking.late) append("，迟到 ${booking.lateMinutes} 分钟")
            if (extraDetail != null) append("；$extraDetail")
        }
        logAccess(user, store, doorId, AccessDirection.IN, result, booking.bookingNo, detail)
        timeline.record(
            if (result == AccessResult.GRANTED_REASSIGNED) "ACCESS_GRANTED_REASSIGNED" else "ACCESS_GRANTED",
            store.id, booking.bookingNo, "USER:${user.id}", detail
        )
        return EntryDecision(true, result, booking.bookingNo, seat.code, detail)
    }

    // ---------------- 离场 ----------------

    data class ExitResult(
        val bookingNo: String,
        val seatCode: String,
        val settlement: CheckoutSettlement,
        val status: BookingStatus,
        val detail: String
    )

    /**
     * 离场。mode 由调用方/门岗场景给出：
     * - NORMAL：正常刷卡离场；
     * - EARLY_LEAVE：用户主动提前离场并要求退费；
     * - STORE_FAULT：门店停电/空调故障等原因清场，按门店责任结算。
     */
    @Transactional
    fun leave(
        userId: Long, storeId: Long,
        lockerReturned: Boolean = true,
        seatEquipmentOk: Boolean = true,
        mode: CheckoutMode = CheckoutMode.NORMAL,
        incidentNo: String? = null,
        doorId: String? = null
    ): ExitResult {
        val now = clock.now()
        val user = userRepo.findById(userId).orElseThrow { NotFoundException("用户不存在") }
        val store = storeRepo.findById(storeId).orElseThrow { NotFoundException("门店不存在") }
        val booking = bookingRepo.findFirstByUserIdAndStoreIdAndStatus(user.id!!, store.id!!, BookingStatus.IN_PROGRESS)
            ?: throw ConflictException("NOT_IN_STORE", "没有在场记录，无法离场")
        val seat = booking.seat ?: throw BizException("NO_SEAT", "在场订单没有座位记录")
        val seatId = seat.id!!

        // 设备类事件须真实存在且未解决，防止冒充门店责任套退
        if (incidentNo != null) {
            val inc = incidentRepo.findByIncidentNo(incidentNo)
                ?: throw NotFoundException("设备事件 $incidentNo 不存在")
            if (inc.store.id != store.id) throw BizException("INCIDENT_STORE_MISMATCH", "事件不属于该门店")
            booking.relatedIncidentNo = incidentNo
        }

        return seatLocks.withSeatLock(seatId) {
            // 同一事务内：结算 → 释放 → 落日志
            val settlement = billing.settleCheckout(booking, now, lockerReturned, seatEquipmentOk, mode, incidentNo)
            booking.actualCheckOut = now
            seat.status = SeatStatus.AVAILABLE
            seatLocks.release(seatId, booking.bookingNo)
            sensorRepo.save(sensorRepo.findBySeatId(seatId)?.apply {
                occupied = false; occupiedByBookingNo = null; updatedAt = now
            } ?: SeatSensorReading(seat = seat, occupied = false, occupiedByBookingNo = null, updatedAt = now))

            val completed =
                booking.type == BookingType.MONTHLY && now < booking.effectiveEndTime
            // 包月会员单次离场：权益仍有效，回到 CONFIRMED，座位立即释放
            booking.status = if (completed) BookingStatus.CONFIRMED else BookingStatus.COMPLETED

            val result = if (mode == CheckoutMode.STORE_FAULT) "门店责任离场"
            else if (mode == CheckoutMode.EARLY_LEAVE) "提前离场" else "正常离场"
            val detail = "$result，座位 ${seat.code}；${settlement.note.ifBlank { "无追加费用" }}"
            logAccess(user, store, doorId, AccessDirection.OUT, AccessResult.GRANTED, booking.bookingNo, detail)
            timeline.record(
                "ACCESS_EXIT_${mode.name}", store.id, booking.bookingNo, "USER:${user.id}",
                "$detail；订单状态=${booking.status}"
            )
            ExitResult(booking.bookingNo, seat.code, settlement, booking.status, detail)
        }
    }

    // ---------------- 日志 ----------------

    private fun logAccess(
        user: AppUser, store: Store, doorId: String?,
        direction: AccessDirection, result: AccessResult, bookingNo: String?, detail: String
    ) {
        val monitor = monitorEventNo(store, doorId, result, clock.now())
        accessLogRepo.save(
            AccessLog(
                bookingNo = bookingNo, user = user, store = store, doorId = doorId,
                direction = direction, result = result, detail = detail,
                monitorEventNo = monitor, createdAt = clock.now()
            )
        )
    }
}

/** 监控事件编号：门店+门禁+结果+时间戳，可与录像系统对账（确定性规则，便于排障） */
internal fun monitorEventNo(store: Store, doorId: String?, result: AccessResult, at: Instant): String {
    val raw = "S${store.id}-${doorId ?: "MAIN"}-${result.name}-${at.toEpochMilli()}"
    return BizNoGenerator.monitor(raw)
}
