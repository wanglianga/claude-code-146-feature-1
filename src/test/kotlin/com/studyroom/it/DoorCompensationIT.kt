package com.studyroom.it

import com.studyroom.domain.*
import com.studyroom.repo.*
import com.studyroom.service.DoorCompensationCommand
import com.studyroom.service.DoorCompensationService
import com.studyroom.support.BizException
import com.studyroom.support.SeatLockManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/**
 * 门禁打不开的自动补偿：
 * - 确认门禁故障 → 延长/退费 + 门店维修工单 + 人工通道放行；
 * - 补偿后回查同窗扫码失败 → 整店异常批量补偿 → 客服逐个确认是否实际进入；
 * - 未到时间 / 走错门店 / 单账号异常（未支付、定位差异）留证驳回，防止套全额退款。
 */
class DoorCompensationIT : BaseIT() {

    @Autowired lateinit var fx: Fixtures
    @Autowired lateinit var doorComp: DoorCompensationService
    @Autowired lateinit var accessRepo: AccessLogRepository
    @Autowired lateinit var incidentRepo: IncidentRepository
    @Autowired lateinit var repairRepo: RepairTicketRepository
    @Autowired lateinit var doorEventRepo: StoreDoorEventRepository
    @Autowired lateinit var redis: StringRedisTemplate

    /** 种子门店附近定位（约十几米）与千里之外定位（北京，用于定位差异反证） */
    private val nearStore1 = doubleArrayOf(31.2305, 121.4738)
    private val farAway = doubleArrayOf(39.9042, 116.4074)

    private fun scan(
        user: AppUser, store: Store, code: DoorErrorCode = DoorErrorCode.MECHANICAL_JAM,
        lat: Double? = nearStore1[0], lng: Double? = nearStore1[1], doorId: String? = "GATE-1"
    ) = doorComp.reportScan(user.id!!, store.id!!, code, doorId, lat, lng)

    private fun currentWindow(): Pair<Instant, Instant> {
        val t = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        return t.minus(Duration.ofMinutes(5)) to t.plus(Duration.ofMinutes(55))
    }

    @Test
    fun `confirmed door fault extends time grants manual entry opens repair ticket and reviews single account`() {
        val store = fx.store1()
        val seat = fx.seat(store.id!!, "A01")
        val user = fx.verifiedUser("门禁故障小明", "13760000001")
        val (start, end) = currentWindow()
        val booking = fx.paidTimedBooking(user, store, seat, start, end)

        // 到店扫码失败：初判疑似门禁故障，证据齐全
        val attempt = scan(user, store)
        assertThat(attempt.classification).isEqualTo(DoorFailureClassification.DOOR_FAULT_SUSPECTED)
        assertThat(attempt.status).isEqualTo(DoorAttemptStatus.PENDING)
        assertThat(attempt.paymentStatus).isEqualTo("PAID")
        assertThat(attempt.locationDistanceMeters!!).isLessThan(300.0)
        assertThat(attempt.suggestedReply).contains(booking.bookingNo)

        // 客服确认故障：延长 60 分钟并经人工通道放行
        val result = doorComp.confirmFault(
            attempt.attemptNo,
            DoorCompensationCommand(CompensationType.EXTEND_TIME, minutes = 60, grantManualEntry = true)
        )

        val after = fx.bookings.findByBookingNo(booking.bookingNo)!!
        assertThat(after.status).isEqualTo(BookingStatus.IN_PROGRESS)
        assertThat(after.actualCheckIn).isNotNull()
        assertThat(after.effectiveEndTime).isEqualTo(end.plus(Duration.ofMinutes(60)))
        assertThat(after.extraChargedAmount.compareTo(BigDecimal.ZERO)).isEqualTo(0) // 故障补偿免收延时费
        assertThat(after.late).isFalse() // 闸机等待不算用户迟到
        assertThat(result.manualEntry?.granted).isTrue()
        assertThat(result.batchEventNo).isNull() // 只有一人，不构成整店事件

        // 设备事件 + 门店维修工单 + 客服工单全部生成
        val inc = incidentRepo.findByIncidentNo(result.incidentNo)!!
        assertThat(inc.type).isEqualTo(IncidentType.DOOR_FAILURE)
        assertThat(inc.status).isEqualTo(IncidentStatus.OPEN)
        val repair = repairRepo.findByRepairNo(result.repairNo)
        assertThat(repair).isNotNull
        val savedAttempt = doorComp.getAttempt(attempt.attemptNo)
        assertThat(savedAttempt.status).isEqualTo(DoorAttemptStatus.CONFIRMED_FAULT)
        assertThat(savedAttempt.supportTicketNo).isNotBlank()

        // 门禁日志与监控编号留存
        val logs = accessRepo.findByBookingNoOrderByCreatedAtAsc(booking.bookingNo)
        assertThat(logs).anyMatch { it.result == AccessResult.DENIED_DOOR_FAULT && it.monitorEventNo != null }
        assertThat(redis.hasKey(SeatLockManager.stateKey(seat.id!!))).isTrue()

        // 维修完成联动关闭设备事件后，门禁恢复正常判定（不再是 DOOR_FAULT）
        doorComp.resolveRepairTicket(result.repairNo, "更换闸机电机并复测", "维修班-王", resolveIncident = true)
        assertThat(incidentRepo.findByIncidentNo(result.incidentNo)!!.status).isEqualTo(IncidentStatus.RESOLVED)
    }

    @Test
    fun `confirmed door fault with fee refund returns full affected period and closes booking without entry`() {
        val store = fx.store1()
        val seat = fx.seat(store.id!!, "A02")
        val user = fx.verifiedUser("放弃进入小红", "13760000002")
        val (start, end) = currentWindow()
        val booking = fx.paidTimedBooking(user, store, seat, start, end)

        val attempt = scan(user, store, DoorErrorCode.DEVICE_OFFLINE)
        val result = doorComp.confirmFault(
            attempt.attemptNo,
            DoorCompensationCommand(CompensationType.REFUND_FEE, grantManualEntry = false)
        )

        val after = fx.bookings.findByBookingNo(booking.bookingNo)!!
        assertThat(after.status).isEqualTo(BookingStatus.COMPLETED)
        assertThat(after.actualCheckIn).isNull()
        assertThat(after.relatedIncidentNo).isEqualTo(result.incidentNo)
        // 未进场：使用费 + 押金全额退
        assertThat(after.refundedAmount.compareTo(booking.price.add(booking.deposit))).isEqualTo(0)
        assertThat(doorComp.getAttempt(attempt.attemptNo).compensatedAmount!!.compareTo(after.refundedAmount)).isEqualTo(0)
        assertThat(redis.hasKey(SeatLockManager.stateKey(seat.id!!))).isFalse()
    }

    @Test
    fun `store-wide door anomaly batch-compensates peers and staff confirms entry one by one`() {
        val store = fx.store1()
        val seatA = fx.seat(store.id!!, "A01")
        val seatB = fx.seat(store.id!!, "A02")
        val seatC = fx.seat(store.id!!, "A03")
        val u1 = fx.verifiedUser("批次甲", "13760000011")
        val u2 = fx.verifiedUser("批次乙", "13760000012")
        val u3 = fx.verifiedUser("批次丙", "13760000013")
        val (start, end) = currentWindow()
        val b1 = fx.paidTimedBooking(u1, store, seatA, start, end)
        val b2 = fx.paidTimedBooking(u2, store, seatB, start, end)
        val b3 = fx.paidTimedBooking(u3, store, seatC, start, end)

        val a1 = scan(u1, store)
        val a2 = scan(u2, store)
        val a3 = scan(u3, store)

        // 客服确认第一名用户：延长+人工放行；回查发现另外两人，达整店阈值，批量延长但不自动放行
        val result = doorComp.confirmFault(
            a1.attemptNo,
            DoorCompensationCommand(CompensationType.EXTEND_TIME, minutes = 60)
        )
        assertThat(result.batchEventNo).isNotBlank()
        assertThat(result.batchAttemptNos).containsExactlyInAnyOrder(a2.attemptNo, a3.attemptNo)

        val event = doorComp.getBatch(result.batchEventNo!!)
        assertThat(event.totalAttempts).isEqualTo(3)
        assertThat(event.enteredCount).isEqualTo(1) // 仅锚定用户当场人工放行
        assertThat(event.status).isEqualTo(CompensationBatchStatus.PENDING_ENTRY_CHECK)

        val peer2 = doorComp.getAttempt(a2.attemptNo)
        val peer3 = doorComp.getAttempt(a3.attemptNo)
        assertThat(peer2.status).isEqualTo(DoorAttemptStatus.BATCH_COMPENSATED)
        assertThat(peer3.status).isEqualTo(DoorAttemptStatus.BATCH_COMPENSATED)
        // 批量补偿只延长窗口，不替用户进场
        assertThat(fx.bookings.findByBookingNo(b2.bookingNo)!!.status).isEqualTo(BookingStatus.CONFIRMED)
        assertThat(fx.bookings.findByBookingNo(b3.bookingNo)!!.status).isEqualTo(BookingStatus.CONFIRMED)
        assertThat(fx.bookings.findByBookingNo(b2.bookingNo)!!.effectiveEndTime).isEqualTo(end.plus(Duration.ofMinutes(60)))

        // 客服逐个核实：乙实际进入 → 补登人工通道放行
        doorComp.confirmBatchEntry(event.eventNo, a2.attemptNo, entered = true)
        assertThat(fx.bookings.findByBookingNo(b2.bookingNo)!!.status).isEqualTo(BookingStatus.IN_PROGRESS)
        assertThat(redis.hasKey(SeatLockManager.stateKey(seatB.id!!))).isTrue()

        // 丙未实际进入 → 延长无意义，改按受影响时段全额退并关闭订单
        doorComp.confirmBatchEntry(event.eventNo, a3.attemptNo, entered = false, note = "监控未见其进店")
        val cAfter = fx.bookings.findByBookingNo(b3.bookingNo)!!
        assertThat(cAfter.status).isEqualTo(BookingStatus.COMPLETED)
        assertThat(cAfter.refundedAmount.compareTo(b3.price.add(b3.deposit))).isEqualTo(0)
        assertThat(redis.hasKey(SeatLockManager.stateKey(seatC.id!!))).isFalse()

        val finished = doorComp.getBatch(event.eventNo)
        assertThat(finished.status).isEqualTo(CompensationBatchStatus.ALL_CHECKED)
        assertThat(finished.enteredCount).isEqualTo(2)
        assertThat(finished.notEnteredCount).isEqualTo(1)
        // 同一设备事件只有一张维修工单
        assertThat(repairRepo.findByIncidentNo(event.incidentNo)).hasSize(1)
    }

    @Test
    fun `refund batch revises over-refund when user is confirmed actually entered`() {
        val store = fx.store1()
        val users = listOf("退费甲" to "13760000021", "退费乙" to "13760000022", "退费丙" to "13760000023")
        val seats = listOf("A01", "A02", "A03").map { fx.seat(store.id!!, it) }
        val (start, end) = currentWindow()
        val bookings = users.mapIndexed { i, (name, phone) ->
            val u = fx.verifiedUser(name, phone)
            fx.paidTimedBooking(u, store, seats[i], start, end)
        }
        val attempts = users.map { (name, phone) ->
            scan(fx.users.findByPhone(phone)!!, store)
        }

        val result = doorComp.confirmFault(
            attempts[0].attemptNo,
            DoorCompensationCommand(CompensationType.REFUND_FEE, grantManualEntry = false)
        )
        val eventNo = result.batchEventNo!!
        // 三名用户全部按“未进入”全额退
        bookings.forEach { b ->
            assertThat(fx.bookings.findByBookingNo(b.bookingNo)!!.status).isEqualTo(BookingStatus.COMPLETED)
        }

        // 监控显示第二名用户其实经人工通道进入了：改判应退金额，差额留证转追缴（不自动扣款）
        doorComp.confirmBatchEntry(eventNo, attempts[1].attemptNo, entered = true, note = "监控确认其 20:03 进场")
        val revised = doorComp.getAttempt(attempts[1].attemptNo)
        assertThat(revised.actualEntered).isTrue()
        assertThat(revised.staffNote).contains("追缴")
        // 退款流水不被冲正：系统只留证，金额封顶保护不被绕过
        assertThat(fx.bookings.findByBookingNo(bookings[1].bookingNo)!!.refundedAmount)
            .isEqualByComparingTo(bookings[1].price.add(bookings[1].deposit))

        doorComp.confirmBatchEntry(eventNo, attempts[2].attemptNo, entered = false)
        val event = doorComp.getBatch(eventNo)
        assertThat(event.status).isEqualTo(CompensationBatchStatus.ALL_CHECKED)
        assertThat(event.enteredCount).isEqualTo(1)
        assertThat(event.notEnteredCount).isEqualTo(2)
    }

    @Test
    fun `batch created later backfills earlier individually-compensated victims without double compensation`() {
        val store = fx.store1()
        val seats = listOf("A01", "A02", "A03").map { fx.seat(store.id!!, it) }
        val (start, end) = currentWindow()

        // 第 1 名用户先到、先确认：当时只有一人，逐单补偿，不成立批次
        val u1 = fx.verifiedUser("先发单人", "13760000051")
        val b1 = fx.paidTimedBooking(u1, store, seats[0], start, end)
        val a1 = scan(u1, store)
        val r1 = doorComp.confirmFault(
            a1.attemptNo, DoorCompensationCommand(CompensationType.EXTEND_TIME, minutes = 60)
        )
        assertThat(r1.batchEventNo).isNull()
        val endAfterFirst = fx.bookings.findByBookingNo(b1.bookingNo)!!.effectiveEndTime

        // 随后第 2、3 名用户陆续扫码；确认第 2 人时回查，整店事件成立并把第 1 人回溯纳入
        val u2 = fx.verifiedUser("第二人", "13760000052")
        val u3 = fx.verifiedUser("第三人", "13760000053")
        val b2 = fx.paidTimedBooking(u2, store, seats[1], start, end)
        val b3 = fx.paidTimedBooking(u3, store, seats[2], start, end)
        val a2 = scan(u2, store)
        val a3 = scan(u3, store)
        val r2 = doorComp.confirmFault(
            a2.attemptNo, DoorCompensationCommand(CompensationType.EXTEND_TIME, minutes = 60)
        )

        val eventNo = r2.batchEventNo!!
        // 第 1 人被回溯但不重复补偿：结束时间没有二次延长
        assertThat(fx.bookings.findByBookingNo(b1.bookingNo)!!.effectiveEndTime).isEqualTo(endAfterFirst)
        val backfilled = doorComp.getAttempt(a1.attemptNo)
        assertThat(backfilled.batchNo).isEqualTo(eventNo)

        val event = doorComp.getBatch(eventNo)
        assertThat(event.totalAttempts).isEqualTo(3)
        assertThat(event.enteredCount).isEqualTo(2) // 前两人均已人工放行
        // 第 3 人走批量逐个确认
        doorComp.confirmBatchEntry(eventNo, a3.attemptNo, entered = true)
        assertThat(fx.bookings.findByBookingNo(b3.bookingNo)!!.status).isEqualTo(BookingStatus.IN_PROGRESS)
        assertThat(doorComp.getBatch(eventNo).status).isEqualTo(CompensationBatchStatus.ALL_CHECKED)
        // 同一设备事件，维修工单仍然只有一张
        assertThat(repairRepo.findByIncidentNo(event.incidentNo)).hasSize(1)
    }

    @Test
    fun `fourth victim individually confirmed after batch exists is still counted in the event`() {
        val store = fx.store1()
        val seats = listOf("A01", "A02", "A03").map { fx.seat(store.id!!, it) }
        val (start, end) = currentWindow()
        fun prepare(name: String, phone: String, seat: Seat): Pair<AppUser, Booking> {
            val u = fx.verifiedUser(name, phone)
            val b = fx.paidTimedBooking(u, store, seat, start, end)
            return u to b
        }
        val (u1, _) = prepare("批四人甲", "13760000061", seats[0])
        val (u2, _) = prepare("批四人乙", "13760000062", seats[1])
        prepare("批四人丙", "13760000063", seats[2])
        val u4 = fx.verifiedUser("批四人丁", "13760000064")
        // 第 4 人窗口更宽：35 分钟后仍在进场时段
        val b4 = fx.paidTimedBooking(
            u4, store, fx.seat(store.id!!, "B01"),
            start.minus(Duration.ofMinutes(5)), end.plus(Duration.ofMinutes(60))
        )
        val a1 = scan(u1, store); scan(u2, store); scan(fx.users.findByPhone("13760000063")!!, store)

        // 确认甲：回查发现甲/乙/丙三人，批次成立，乙/丙批量补偿待确认
        val r1 = doorComp.confirmFault(a1.attemptNo, DoorCompensationCommand(CompensationType.EXTEND_TIME, minutes = 60))
        val eventNo = r1.batchEventNo!!
        assertThat(doorComp.getBatch(eventNo).totalAttempts).isEqualTo(3)

        // 35 分钟后第 4 人才扫码并被单独确认：已不在首批 ±30 分钟回查窗口，但同属未解决的门禁事件
        clock.setOverride(clock.now().plus(Duration.ofMinutes(35)))
        val a4 = scan(u4, store, doorId = "GATE-1")
        val r4 = doorComp.confirmFault(a4.attemptNo, DoorCompensationCommand(CompensationType.EXTEND_TIME, minutes = 60))
        assertThat(r4.batchEventNo).isEqualTo(eventNo)
        val event = doorComp.getBatch(eventNo)
        assertThat(event.totalAttempts).isEqualTo(4)
        assertThat(doorComp.getAttempt(a4.attemptNo).batchNo).isEqualTo(eventNo)
        assertThat(fx.bookings.findByBookingNo(b4.bookingNo)!!.status).isEqualTo(BookingStatus.IN_PROGRESS)
    }

    @Test
    fun `wrong store and early arrival replies cite order and location evidence and cannot be confirmed as fault`() {
        val store1 = fx.store1()
        val store2 = fx.store2()

        // 走错门店：预约在 store2，人在 store1 扫码
        val wrong = fx.verifiedUser("走错门店", "13760000031")
        val (s1, e1) = currentWindow()
        val bookingAtStore2 = fx.paidTimedBooking(wrong, store2, fx.seat(store2.id!!, "C01"), s1, e1)
        val wrongAttempt = doorComp.reportScan(
            wrong.id!!, store1.id!!, DoorErrorCode.READER_FAILURE, "GATE-1",
            nearStore1[0], nearStore1[1]
        )
        assertThat(wrongAttempt.classification).isEqualTo(DoorFailureClassification.WRONG_STORE)
        assertThat(wrongAttempt.suggestedReply).contains(bookingAtStore2.bookingNo, store2.name, store2.address)
        assertThat(wrongAttempt.bookingStoreId).isEqualTo(store2.id)
        assertBizCode("NOT_DOOR_FAULT") {
            doorComp.confirmFault(wrongAttempt.attemptNo, DoorCompensationCommand(CompensationType.EXTEND_TIME))
        }
        val rejected = doorComp.reject(wrongAttempt.attemptNo, "已引导用户前往预约门店")
        assertThat(rejected.status).isEqualTo(DoorAttemptStatus.REJECTED)
        assertThat(rejected.supportTicketNo).isNotBlank()

        // 预约时间未到：两小时后的订单现在扫码
        val early = fx.verifiedUser("来得太早", "13760000032")
        val t = Instant.now()
        val earlyBooking = fx.paidTimedBooking(
            early, store1, fx.seat(store1.id!!, "A01"),
            t.plus(Duration.ofHours(2)), t.plus(Duration.ofHours(3))
        )
        val earlyAttempt = scan(early, store1)
        assertThat(earlyAttempt.classification).isEqualTo(DoorFailureClassification.EARLY_ARRIVAL)
        assertThat(earlyAttempt.paymentStatus).isEqualTo("PAID")
        assertThat(earlyAttempt.suggestedReply).contains(earlyBooking.bookingNo)
        doorComp.reject(earlyAttempt.attemptNo, "未到进场时间，已向用户出示订单时间证据")
        assertThat(doorComp.getAttempt(earlyAttempt.attemptNo).status).isEqualTo(DoorAttemptStatus.REJECTED)
    }

    @Test
    fun `single account anomalies keep scan logs and location diff and block full refund claims`() {
        val store = fx.store1()

        // 已支付但定位在千里之外：无法证明到店，降级单账号异常
        val remote = fx.verifiedUser("定位异常", "13760000041")
        val (s, e) = currentWindow()
        val paid = fx.paidTimedBooking(remote, store, fx.seat(store.id!!, "A01"), s, e)
        val remoteAttempt = doorComp.reportScan(
            remote.id!!, store.id!!, DoorErrorCode.UNKNOWN_DEVICE, "GATE-1", farAway[0], farAway[1]
        )
        assertThat(remoteAttempt.classification).isEqualTo(DoorFailureClassification.ACCOUNT_NOT_ELIGIBLE)
        assertThat(remoteAttempt.locationDistanceMeters!!).isGreaterThan(100_000.0)
        assertThat(remoteAttempt.suggestedReply).contains(paid.bookingNo, "定位")
        assertBizCode("NOT_DOOR_FAULT") {
            doorComp.confirmFault(remoteAttempt.attemptNo, DoorCompensationCommand(CompensationType.REFUND_FEE))
        }
        doorComp.reject(remoteAttempt.attemptNo, "定位与门店差异过大，不支持设备故障全额退款")
        // 钱没有退：扫码日志与定位差异永久保留
        assertThat(fx.bookings.findByBookingNo(paid.bookingNo)!!.refundedAmount.compareTo(BigDecimal.ZERO)).isEqualTo(0)

        // 未支付：支付状态快照 UNPAID，同样不补偿
        val unpaid = fx.verifiedUser("忘了支付", "13760000042")
        val rawBooking = fx.bookingService.create(
            com.studyroom.service.CreateBookingCommand(
                userId = unpaid.id!!, storeId = store.id!!, seatId = fx.seat(store.id!!, "A02").id,
                type = BookingType.SINGLE, startTime = s, endTime = e, silenceRuleAcknowledged = true
            )
        )
        val unpaidAttempt = scan(unpaid, store)
        assertThat(unpaidAttempt.classification).isEqualTo(DoorFailureClassification.ACCOUNT_NOT_ELIGIBLE)
        assertThat(unpaidAttempt.paymentStatus).isEqualTo("UNPAID")
        assertThat(unpaidAttempt.bookingNo).isEqualTo(rawBooking.bookingNo)

        // 无预约：支付状态 NONE，记录保留但不进入故障流程
        val nobody = fx.verifiedUser("无订单路人", "13760000043")
        val noneAttempt = scan(nobody, store)
        assertThat(noneAttempt.classification).isEqualTo(DoorFailureClassification.ACCOUNT_NOT_ELIGIBLE)
        assertThat(noneAttempt.paymentStatus).isEqualTo("NONE")
        assertThat(noneAttempt.bookingNo).isNull()
        doorComp.reject(noneAttempt.attemptNo, "无有效预约")
        assertThat(doorComp.listAttempts(store.id!!, DoorAttemptStatus.REJECTED)).isNotEmpty
    }

    private fun assertBizCode(expected: String, block: () -> Unit) {
        try {
            block()
            error("应抛出业务异常 $expected")
        } catch (e: BizException) {
            assertThat(e.code).isEqualTo(expected)
        }
    }
}
