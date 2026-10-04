package com.studyroom.it

import com.studyroom.domain.*
import com.studyroom.repo.DoorCompensationBatchRepository
import com.studyroom.repo.DoorScanAttemptRepository
import com.studyroom.repo.IncidentRepository
import com.studyroom.service.AccessService
import com.studyroom.service.CompensationPreference
import com.studyroom.service.DoorCompensationService
import com.studyroom.service.DoorScanCommand
import com.studyroom.service.IncidentService
import com.studyroom.service.SupportService
import com.studyroom.support.BizException
import com.studyroom.support.SeatLockManager
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.redis.core.StringRedisTemplate
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 门禁打不开自动补偿：
 * 扫码留证 → 客服确认故障（免费延长/退受影响时段费）→ 维修工单 → 同店同时段回查整店批量补偿 →
 * 逐人确认进入；预约未到/走错门店引用订单与定位证据回复；单账号异常留证防套退；
 * 免费延长碰撞后续订单时自动回退退费。
 */
class DoorCompensationIT : BaseIT() {

    @Autowired lateinit var fx: Fixtures
    @Autowired lateinit var doorComp: DoorCompensationService
    @Autowired lateinit var support: SupportService
    @Autowired lateinit var incidents: IncidentService
    @Autowired lateinit var access: AccessService
    @Autowired lateinit var scanRepo: DoorScanAttemptRepository
    @Autowired lateinit var batchRepo: DoorCompensationBatchRepository
    @Autowired lateinit var incidentRepo: IncidentRepository
    @Autowired lateinit var redis: StringRedisTemplate

    private fun t0(): Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)

    private fun scanCmd(
        user: AppUser, store: Store, code: DoorErrorCode = DoorErrorCode.GATE_NOT_OPENING,
        lat: Double? = null, lon: Double? = null, doorId: String? = "GATE-1"
    ) = DoorScanCommand(user.id!!, store.id!!, code, doorId, lat, lon)

    private fun scan(no: String) = scanRepo.findByScanNo(no)!!

    @Test
    fun `confirmed door fault gives free extension, repair incident and extended late grace`() {
        val t = t0(); clock.setOverride(t)
        val store = fx.store1()
        val seat = fx.seat(store.id!!, "A01")
        val user = fx.verifiedUser("门禁补偿-小明", "13760000001")
        // 预约 5 分钟前开始、115 分钟后结束，用户到店扫码失败
        val b = fx.paidTimedBooking(user, store, seat, t.minus(Duration.ofMinutes(5)), t.plus(Duration.ofMinutes(115)))

        val failed = doorComp.recordScanFailure(scanCmd(user, store))
        assertThat(failed.classification).isEqualTo(DoorScanClassification.SUSPECTED_DOOR_FAULT)
        assertThat(failed.status).isEqualTo(DoorScanStatus.TICKETED)
        assertThat(failed.monitorEventNo).isNotBlank
        assertThat(failed.paymentSnapshot).isEqualTo("CONFIRMED")

        // 20 分钟后客服确认门禁故障 → 免费延长
        clock.setOverride(t.plus(Duration.ofMinutes(20)))
        val confirmed = doorComp.confirmDoorFault(failed.scanNo, CompensationPreference.EXTEND, staffId = 900L)
        assertThat(confirmed.storeWide).isFalse()
        val after = fx.bookings.findByBookingNo(b.bookingNo)!!
        assertThat(after.compEndTime).isEqualTo(t.plus(Duration.ofMinutes(135))) // 原结束 t+115 再延 20
        assertThat(after.compGraceMinutes).isEqualTo(20L)

        // 生成门店维修工单（Incident），处于 OPEN
        val repair = incidentRepo.findByIncidentNo(confirmed.scan.incidentNo!!)!!
        assertThat(repair.type).isEqualTo(IncidentType.DOOR_FAILURE)
        assertThat(repair.status).isEqualTo(IncidentStatus.OPEN)

        // 维修完成前，其他用户进场仍被门禁故障拦截
        val other = fx.verifiedUser("其他用户", "13760000002")
        fx.paidTimedBooking(other, store, fx.seat(store.id!!, "A02"), t.minus(Duration.ofMinutes(5)), t.plus(Duration.ofMinutes(60)))
        assertThat(access.enter(other.id!!, store.id!!).result).isEqualTo(AccessResult.DENIED_DOOR_FAULT)

        // 维修完成后，受补偿用户在确认时刻（t+20）进场：迟到 25 分钟，超门店 15 分钟宽限但在 35 分钟补偿宽限内，放行
        incidents.resolve(repair.incidentNo, "更换闸机主板，门禁恢复")
        val decision = access.enter(user.id!!, store.id!!)
        assertThat(decision.granted).isTrue()
        val inProgress = fx.bookings.findByBookingNo(b.bookingNo)!!
        assertThat(inProgress.status).isEqualTo(BookingStatus.IN_PROGRESS)
        assertThat(inProgress.lateMinutes).isEqualTo(25L)
    }

    @Test
    fun `recheck finds other failed users and creates store-wide batch confirmed one by one`() {
        val t = t0(); clock.setOverride(t)
        val store = fx.store1()
        val u1 = fx.verifiedUser("批量-甲", "13760000011")
        val u2 = fx.verifiedUser("批量-乙", "13760000012")
        val u3 = fx.verifiedUser("批量-丙", "13760000013")
        val b1 = fx.paidTimedBooking(u1, store, fx.seat(store.id!!, "A01"), t.minus(Duration.ofMinutes(5)), t.plus(Duration.ofMinutes(55)))
        fx.paidTimedBooking(u2, store, fx.seat(store.id!!, "A02"), t.minus(Duration.ofMinutes(5)), t.plus(Duration.ofMinutes(55)))
        fx.paidTimedBooking(u3, store, fx.seat(store.id!!, "A03"), t.minus(Duration.ofMinutes(5)), t.plus(Duration.ofMinutes(55)))

        val s1 = doorComp.recordScanFailure(scanCmd(u1, store, DoorErrorCode.GATE_NOT_OPENING))
        clock.setOverride(t.plus(Duration.ofMinutes(2)))
        val s2 = doorComp.recordScanFailure(scanCmd(u2, store, DoorErrorCode.READER_NO_RESPONSE))
        clock.setOverride(t.plus(Duration.ofMinutes(4)))
        val s3 = doorComp.recordScanFailure(scanCmd(u3, store, DoorErrorCode.DEVICE_OFFLINE))

        // 甲在第 10 分钟被确认故障 → 回查发现乙、丙 → 整店批量
        clock.setOverride(t.plus(Duration.ofMinutes(10)))
        val r1 = doorComp.confirmDoorFault(s1.scanNo, CompensationPreference.EXTEND)
        assertThat(r1.storeWide).isTrue()
        val batch = r1.batch!!
        assertThat(batch.affectedCount).isEqualTo(2)
        assertThat(batch.status).isEqualTo(DoorScanStatus.BATCH_PENDING_CONFIRM)

        assertThat(scan(s2.scanNo).status).isEqualTo(DoorScanStatus.BATCH_PENDING_CONFIRM)
        assertThat(scan(s2.scanNo).batchNo).isEqualTo(batch.batchNo)
        assertThat(scan(s3.scanNo).classification).isEqualTo(DoorScanClassification.STORE_WIDE_DOOR_FAULT)

        // 乙确认实际进入（第 8 分钟进门）→ 免费延长 6 分钟
        val c2 = doorComp.confirmBatchEntry(s2.scanNo, actuallyEntered = true, enteredAt = t.plus(Duration.ofMinutes(8)), staffId = 900L)
        assertThat(c2.status).isEqualTo(DoorScanStatus.BATCH_CONFIRMED_ENTERED)
        assertThat(c2.compensationType).isEqualTo(CompensationType.FREE_EXTENSION)
        assertThat(c2.compensationMinutes).isEqualTo(6L)

        // 丙确认未能进入 → 退受影响时段费用
        val c3 = doorComp.confirmBatchEntry(s3.scanNo, actuallyEntered = false, staffId = 900L)
        assertThat(c3.status).isEqualTo(DoorScanStatus.BATCH_REFUNDED_NOT_ENTERED)
        assertThat(c3.compensationType).isEqualTo(CompensationType.AFFECTED_PERIOD_REFUND)
        assertThat(fx.bookings.findByBookingNo(s3.bookingNo!!)!!.refundedAmount)
            .isGreaterThan(BigDecimal.ZERO)

        // 全部确认后批次自动结案，且只生成一个维修事件
        val batchDone = batchRepo.findByBatchNo(batch.batchNo)!!
        assertThat(batchDone.status).isEqualTo(DoorScanStatus.BATCH_RESOLVED)
        assertThat(batchDone.confirmedCount).isEqualTo(1)
        assertThat(batchDone.refundedCount).isEqualTo(1)
        assertThat(fx.bookings.findByBookingNo(b1.bookingNo)!!.compGraceMinutes).isEqualTo(10L)
    }

    @Test
    fun `new hardware scan auto-attaches to an open store-wide door incident`() {
        val t = t0(); clock.setOverride(t)
        val store = fx.store1()
        val inc = incidents.open(store.id!!, IncidentType.DOOR_FAILURE, "正门闸机主板烧毁")

        val user = fx.verifiedUser("并入批次", "13760000021")
        fx.paidTimedBooking(user, store, fx.seat(store.id!!, "A01"), t.minus(Duration.ofMinutes(5)), t.plus(Duration.ofMinutes(55)))
        val failed = doorComp.recordScanFailure(scanCmd(user, store))

        assertThat(failed.status).isEqualTo(DoorScanStatus.BATCH_PENDING_CONFIRM)
        assertThat(failed.classification).isEqualTo(DoorScanClassification.STORE_WIDE_DOOR_FAULT)
        assertThat(failed.incidentNo).isEqualTo(inc.incidentNo)
        assertThat(failed.batchNo).isNotBlank
    }

    @Test
    fun `booking not started reply cites order and time window`() {
        val t = t0(); clock.setOverride(t)
        val store = fx.store1()
        val user = fx.verifiedUser("提前到店", "13760000031")
        val b = fx.paidTimedBooking(user, store, fx.seat(store.id!!, "A01"), t.plus(Duration.ofMinutes(60)), t.plus(Duration.ofMinutes(120)))

        val failed = doorComp.recordScanFailure(scanCmd(user, store))
        assertThat(failed.classification).isEqualTo(DoorScanClassification.BOOKING_NOT_STARTED)
        assertThat(failed.status).isEqualTo(DoorScanStatus.TICKETED)

        // 不允许借“确认门禁故障”套取自动赔付
        assertThatThrownBy { doorComp.confirmDoorFault(failed.scanNo, CompensationPreference.EXTEND) }
            .isInstanceOf(BizException::class.java)
            .hasMessageContaining("BOOKING_NOT_STARTED")

        val replied = doorComp.replyWithEvidence(
            failed.scanNo, "您的预约开始时间未到，请在可进场窗口内到店扫码", staffId = 900L
        )
        assertThat(replied.status).isEqualTo(DoorScanStatus.REJECTED_USER_SIDE)
        assertThat(replied.replyNote).contains(b.bookingNo).contains("预约时段")
        // 工单证据包包含扫码记录，且判用户侧
        val ev = support.evidence(replied.ticketNo!!)
        assertThat(ev.doorScans.map { it.scanNo }).contains(failed.scanNo)
        assertThat(ev.ticket.responsibility).isEqualTo(Responsibility.USER)
    }

    @Test
    fun `wrong store reply cites order belonging to other store and location gap`() {
        val t = t0(); clock.setOverride(t)
        val store1 = fx.store1()
        val store2 = fx.store2()
        val user = fx.verifiedUser("走错门店", "13760000041")
        val booking = fx.paidTimedBooking(
            user, store2, fx.seat(store2.id!!, "C01"), t.minus(Duration.ofMinutes(5)), t.plus(Duration.ofMinutes(55))
        )

        // 人在 store2 附近，却到 store1 扫码
        val failed = doorComp.recordScanFailure(
            scanCmd(user, store1, lat = store2.latitude, lon = store2.longitude)
        )
        assertThat(failed.classification).isEqualTo(DoorScanClassification.WRONG_STORE)
        assertThat(failed.bookingNo).isEqualTo(booking.bookingNo)
        assertThat(failed.locationDistanceMeters!!).isGreaterThan(1000.0) // 两店相距数公里

        val replied = doorComp.replyWithEvidence(failed.scanNo, "您的有效预约在科技园门店，请来对门店", staffId = 900L)
        assertThat(replied.status).isEqualTo(DoorScanStatus.REJECTED_USER_SIDE)
        assertThat(replied.replyNote).contains("其他门店").contains("科技园").contains("米")
    }

    @Test
    fun `single account anomaly keeps scan log and location evidence and blocks auto refund`() {
        val t = t0(); clock.setOverride(t)
        val store = fx.store1()
        val user = fx.verifiedUser("无单硬扫", "13760000051")

        val failed = doorComp.recordScanFailure(
            scanCmd(user, store, DoorErrorCode.INVALID_QRCODE, lat = 31.2304, lon = 121.4737)
        )
        assertThat(failed.classification).isEqualTo(DoorScanClassification.SINGLE_ACCOUNT)
        assertThat(failed.paymentSnapshot).isEqualTo("NO_BOOKING")
        // 用户侧/无单不自动开工单
        assertThat(failed.ticketNo).isNull()

        assertThatThrownBy { doorComp.confirmDoorFault(failed.scanNo, CompensationPreference.REFUND) }
            .isInstanceOf(BizException::class.java)
            .hasMessageContaining("SINGLE_ACCOUNT")

        // 扫码日志长期保留，可按用户审计，防止之后以设备故障名义申请全额退款
        val kept = scanRepo.findByUserIdOrderByScannedAtDesc(user.id!!)
        assertThat(kept).anyMatch {
            it.scanNo == failed.scanNo && it.status == DoorScanStatus.RECORDED &&
                it.locationDistanceMeters != null && it.errorCode == DoorErrorCode.INVALID_QRCODE
        }
    }

    @Test
    fun `free extension colliding with next paid booking falls back to affected-period refund`() {
        val t = t0(); clock.setOverride(t)
        val store = fx.store1()
        val seat = fx.seat(store.id!!, "A01")
        val u1 = fx.verifiedUser("碰撞补偿-甲", "13760000061")
        val u2 = fx.verifiedUser("碰撞补偿-下一位", "13760000062")
        val b1 = fx.paidTimedBooking(u1, store, seat, t.minus(Duration.ofMinutes(5)), t.plus(Duration.ofMinutes(55))) // 60 分钟 10 元
        fx.paidTimedBooking(u2, store, seat, t.plus(Duration.ofMinutes(55)), t.plus(Duration.ofMinutes(115)))

        val failed = doorComp.recordScanFailure(scanCmd(u1, store))
        clock.setOverride(t.plus(Duration.ofMinutes(30)))
        val r = doorComp.confirmDoorFault(failed.scanNo, CompensationPreference.EXTEND) // 等待 30 分钟

        // 想延到 t+85，与下一位 t+55 碰撞 → 回退退费；不延长结束时间
        val after = fx.bookings.findByBookingNo(b1.bookingNo)!!
        assertThat(after.compEndTime).isNull()
        assertThat(r.scan.compensationType).isEqualTo(CompensationType.AFFECTED_PERIOD_REFUND)
        // 受影响 30 分钟 / 总 60 分钟 × 10 元 = 5 元
        assertThat(r.scan.refundAmount.compareTo(BigDecimal("5.00"))).isEqualTo(0)

        // 维修恢复后下一位预约不受影响，仍可在其开始时间正常进场
        incidents.resolve(r.scan.incidentNo!!, "门禁恢复")
        clock.setOverride(t.plus(Duration.ofMinutes(56)))
        assertThat(access.enter(u2.id!!, store.id!!).granted).isTrue()
    }

    @Test
    fun `refund preference returns affected period fee and free-extension checkout charges no overtime`() {
        val t = t0(); clock.setOverride(t)
        val store = fx.store1()
        val user = fx.verifiedUser("退费偏好", "13760000071")
        val b = fx.paidTimedBooking(user, store, fx.seat(store.id!!, "A02"), t.minus(Duration.ofMinutes(10)), t.plus(Duration.ofMinutes(50))) // 60 分钟 10 元

        val failed = doorComp.recordScanFailure(scanCmd(user, store))
        clock.setOverride(t.plus(Duration.ofMinutes(20)))
        val r = doorComp.confirmDoorFault(failed.scanNo, CompensationPreference.REFUND)
        // 扫码在 t、确认在 t+20：等待 20 分钟封顶，受影响 20/60×10 = 3.33 元
        assertThat(r.scan.compensationType).isEqualTo(CompensationType.AFFECTED_PERIOD_REFUND)
        assertThat(r.scan.refundAmount.compareTo(BigDecimal("3.33"))).isEqualTo(0)
        assertThat(fx.bookings.findByBookingNo(b.bookingNo)!!.refundedAmount.compareTo(BigDecimal("3.33"))).isEqualTo(0)

        // 免费延长场景：维修恢复后进場，在免费延长后的结束时间离场，不收超时费
        incidents.resolve(r.scan.incidentNo!!, "门禁恢复")
        val u2 = fx.verifiedUser("延长离场", "13760000072")
        val seat2 = fx.seat(store.id!!, "A03")
        clock.setOverride(t.plus(Duration.ofMinutes(21)))
        val b2 = fx.paidTimedBooking(u2, store, seat2, t.plus(Duration.ofMinutes(20)), t.plus(Duration.ofMinutes(80)))
        val s2 = doorComp.recordScanFailure(scanCmd(u2, store)) // 预约已开始 1 分钟
        clock.setOverride(t.plus(Duration.ofMinutes(30)))
        val r2 = doorComp.confirmDoorFault(s2.scanNo, CompensationPreference.EXTEND) // 等待 9 分钟
        assertThat(r2.storeWide).isFalse()
        // 补偿完成才生成维修事件，维修恢复后用户进场
        incidents.resolve(r2.scan.incidentNo!!, "门禁恢复")
        access.enter(u2.id!!, store.id!!)
        val b2After = fx.bookings.findByBookingNo(b2.bookingNo)!!
        assertThat(b2After.compEndTime).isEqualTo(t.plus(Duration.ofMinutes(89))) // t+80 + 9
        clock.setOverride(b2After.effectiveEndTime.truncatedTo(ChronoUnit.SECONDS))
        val exit = access.leave(u2.id!!, store.id!!)
        assertThat(exit.settlement.overtimeFee.compareTo(BigDecimal.ZERO)).isEqualTo(0)
        assertThat(redis.hasKey(SeatLockManager.stateKey(seat2.id!!))).isFalse()
    }
}
