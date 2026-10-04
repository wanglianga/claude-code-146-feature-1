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
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 门禁故障确认后的补偿偏好：免费延长 / 退受影响时段费（延长遇碰撞时自动回退为退费） */
enum class CompensationPreference { EXTEND, REFUND }

data class DoorScanCommand(
    val userId: Long,
    val storeId: Long,
    val errorCode: DoorErrorCode,
    val doorId: String? = null,
    val clientLat: Double? = null,
    val clientLon: Double? = null
)

/**
 * 门禁打不开的自动补偿服务。
 *
 * 主流程（对应需求）：
 * 1. 用户预约开始后到店、门禁无法识别 → [recordScanFailure] 记录扫码时间、门禁错误码、
 *    支付状态与定位差异，写门禁日志/时间轴；硬件类失败自动开客服工单；
 * 2. 客服核实为门禁故障 → [confirmDoorFault]：开门店维修工单（Incident），
 *    免费延长本次使用时间，或退还受影响时段费用（延长与后续已支付订单碰撞时自动回退退费）；
 * 3. 补偿完成后回查同一门店同一时段是否还有其他用户扫码失败：
 *    - 有 → 判定整店门禁异常，补偿规则按门店事件批量生成批次，客服只需逐人确认是否实际进入
 *      （[confirmBatchEntry]）；
 *    - 无 → 单账号事件；凭证类/无单/未到时间/走错门店记录长期保留，
 *      客服回复引用订单与定位证据（[replyWithEvidence]），防止借设备故障名义套退。
 */
@Service
class DoorCompensationService(
    private val scanRepo: DoorScanAttemptRepository,
    private val batchRepo: DoorCompensationBatchRepository,
    private val bookingRepo: BookingRepository,
    private val userRepo: AppUserRepository,
    private val storeRepo: StoreRepository,
    private val incidentRepo: IncidentRepository,
    private val ticketRepo: SupportTicketRepository,
    private val accessLogRepo: AccessLogRepository,
    private val sensorRepo: SeatSensorRepository,
    private val incidentService: IncidentService,
    private val billing: BillingService,
    private val seatLocks: SeatLockManager,
    private val timeline: TimelineService,
    private val clock: TimeProvider
) {
    data class FaultConfirmation(
        val scan: DoorScanAttempt,
        val batch: DoorCompensationBatch?,
        val storeWide: Boolean
    )

    // ---------------- 扫码失败登记 ----------------

    @Transactional
    fun recordScanFailure(cmd: DoorScanCommand): DoorScanAttempt {
        val now = clock.now()
        val user = userRepo.findById(cmd.userId).orElseThrow { NotFoundException("用户不存在") }
        val store = storeRepo.findById(cmd.storeId).orElseThrow { NotFoundException("门店不存在") }

        val here = bookingRepo.findByUserIdOrderByStartTimeDesc(user.id!!)
            .filter { it.store.id == store.id }
        val activeHere = here.firstOrNull { it.status == BookingStatus.IN_PROGRESS }
            ?: here.firstOrNull {
                it.status == BookingStatus.CONFIRMED &&
                    (it.type == BookingType.MONTHLY || it.startTime <= now) &&
                    it.effectiveEndTime > now
            }
        val upcomingHere = here
            .filter { it.status == BookingStatus.CONFIRMED && it.type != BookingType.MONTHLY && it.startTime > now }
            .minByOrNull { it.startTime }
        val unpaidHere = here.firstOrNull { it.status == BookingStatus.PENDING_PAYMENT && it.effectiveEndTime > now }
        val activeOtherStore = bookingRepo.findByUserIdOrderByStartTimeDesc(user.id!!)
            .firstOrNull {
                it.store.id != store.id &&
                    it.status in listOf(BookingStatus.CONFIRMED, BookingStatus.IN_PROGRESS) &&
                    it.startTime <= now && it.effectiveEndTime > now
            }

        val earlyWindow = store.earlyEntryMinutes.toLong()
        val atValidWindow = activeHere != null ||
            (upcomingHere != null && !now.isBefore(upcomingHere.startTime.minus(Duration.ofMinutes(earlyWindow))))

        val classification = when {
            store.blackout -> DoorScanClassification.STORE_BLACKOUT
            cmd.errorCode.isHardware() && atValidWindow -> DoorScanClassification.SUSPECTED_DOOR_FAULT
            upcomingHere != null && !atValidWindow -> DoorScanClassification.BOOKING_NOT_STARTED
            activeOtherStore != null -> DoorScanClassification.WRONG_STORE
            cmd.errorCode.isHardware() -> DoorScanClassification.NO_BOOKING
            else -> DoorScanClassification.SINGLE_ACCOUNT
        }

        val matched = activeHere ?: upcomingHere ?: unpaidHere
        val distance = haversineMeters(cmd.clientLat, cmd.clientLon, store.latitude, store.longitude)

        val monitor = monitorEventNo(store, cmd.doorId, accessResultFor(classification, cmd.errorCode), now)
        val detail = buildEvidenceDetail(
            classification = classification, errorCode = cmd.errorCode,
            matched = matched, activeOtherStore = activeOtherStore, store = store,
            scannedAt = now, distance = distance
        )

        val scan = scanRepo.save(
            DoorScanAttempt(
                scanNo = "TMP", user = user, store = store, doorId = cmd.doorId,
                errorCode = cmd.errorCode, classification = classification,
                status = DoorScanStatus.RECORDED, scannedAt = now,
                bookingNo = (matched ?: activeOtherStore)?.bookingNo,
                paymentSnapshot = (matched ?: activeOtherStore)?.status?.name ?: "NO_BOOKING",
                bookingStartTime = (matched ?: activeOtherStore)?.startTime,
                bookingEndTime = (matched ?: activeOtherStore)?.let {
                    if (it.type == BookingType.MONTHLY) it.effectiveEndTime else it.endTime
                },
                clientLat = cmd.clientLat, clientLon = cmd.clientLon,
                storeLat = store.latitude, storeLon = store.longitude,
                locationDistanceMeters = distance,
                monitorEventNo = monitor, detail = detail
            )
        )
        scan.scanNo = BizNoGenerator.doorScan(scan.id!!)

        // 门禁日志：与普通进场判定共用一张证据表
        accessLogRepo.save(
            AccessLog(
                bookingNo = (matched ?: activeOtherStore)?.bookingNo, user = user, store = store,
                doorId = cmd.doorId, direction = AccessDirection.IN,
                result = accessResultFor(classification, cmd.errorCode),
                detail = "扫码失败登记 ${scan.scanNo}：$detail",
                monitorEventNo = monitor, createdAt = now
            )
        )
        timeline.record(
            "DOOR_SCAN_FAILED", store.id, (matched ?: activeOtherStore)?.bookingNo, "USER:${user.id}",
            "${scan.scanNo} ${cmd.errorCode.name} 初判 ${classification.name}；$detail"
        )

        // 已存在整店维修事件/进行中批次：新扫码直接并入批量待确认
        val openIncident = incidentRepo.findByStoreIdAndTypeAndStatus(
            store.id!!, IncidentType.DOOR_FAILURE, IncidentStatus.OPEN
        ).firstOrNull()
        val openBatch = batchRepo.findByStoreIdOrderByCreatedAtDesc(store.id!!)
            .firstOrNull { it.status == DoorScanStatus.BATCH_PENDING_CONFIRM }
        if (classification == DoorScanClassification.SUSPECTED_DOOR_FAULT &&
            (openIncident != null || openBatch != null)
        ) {
            attachToStoreWideBatch(scan, openIncident, openBatch, now)
        } else if (classification == DoorScanClassification.SUSPECTED_DOOR_FAULT) {
            // 首起疑似硬件故障：自动开客服工单，等待人工核实（不自动赔付）
            openTicketFor(scan, matched!!, "DOOR_SCAN",
                "门禁扫码失败自动工单：${cmd.errorCode.name}，疑似门禁设备故障，待客服核实")
            scan.status = DoorScanStatus.TICKETED
            timeline.record("DOOR_SCAN_TICKETED", store.id, matched.bookingNo, "SYSTEM",
                "${scan.scanNo} 已开工单 ${scan.ticketNo}")
        } else if (classification == DoorScanClassification.BOOKING_NOT_STARTED ||
            classification == DoorScanClassification.WRONG_STORE
        ) {
            // 有真实预约、但时间/地点不对：开客服工单，回复时引用订单与定位证据
            val related = matched ?: activeOtherStore
            if (related != null) {
                openTicketFor(scan, related, "DOOR_SCAN",
                    "门禁扫码失败自动工单：初判 ${classification.name}，待客服引用证据回复",
                    cause = DisputeCause.OTHER)
                scan.status = DoorScanStatus.TICKETED
                timeline.record("DOOR_SCAN_TICKETED", store.id, related.bookingNo, "SYSTEM",
                    "${scan.scanNo}（${classification.name}）已开工单 ${scan.ticketNo}")
            }
        }
        // SINGLE_ACCOUNT / NO_BOOKING / STORE_BLACKOUT：仅保留扫码日志与定位差异，不自动开工单
        return scan
    }

    // ---------------- 客服确认门禁故障 → 补偿 + 回查 ----------------

    /**
     * 客服核实扫码失败确属门禁故障：
     * 开/复用门店维修工单，按偏好执行免费延长或受影响时段退费；
     * 随后回查同时段其他硬件类扫码失败，若存在则升级为整店批量补偿。
     */
    @Transactional
    fun confirmDoorFault(
        scanNo: String,
        prefer: CompensationPreference,
        affectedMinutes: Long? = null,
        staffId: Long? = null,
        note: String? = null
    ): FaultConfirmation {
        val scan = scanRepo.findByScanNo(scanNo) ?: throw NotFoundException("扫码记录不存在")
        if (scan.status == DoorScanStatus.COMPENSATED) {
            throw ConflictException("SCAN_DONE", "该扫码记录已完成补偿")
        }
        if (scan.status in listOf(
                DoorScanStatus.BATCH_PENDING_CONFIRM,
                DoorScanStatus.BATCH_CONFIRMED_ENTERED,
                DoorScanStatus.BATCH_REFUNDED_NOT_ENTERED
            )
        ) {
            throw ConflictException("USE_BATCH_CONFIRM", "该记录属于整店批量事件，请使用逐人确认接口")
        }
        // 防套退：初判为用户侧原因的扫码不允许走“确认门禁故障”自动赔付；
        // 如监控证明确属设备问题，由用户走投诉工单、客服按证据手工判定退款（仍受退款上限保护）。
        if (scan.classification in USER_SIDE_CLASSIFICATIONS) {
            throw ConflictException(
                "USER_SIDE_SCAN",
                "该扫码初判为 ${scan.classification.name}，请使用证据回复接口；确有设备证据请走投诉工单判定"
            )
        }
        val booking = scan.bookingNo?.let { bookingRepo.findByBookingNo(it) }
            ?: throw BizException("NO_BOOKING", "扫码记录无关联预约，无法执行故障补偿")
        if (booking.status == BookingStatus.CANCELLED || booking.status == BookingStatus.NO_SHOW) {
            throw ConflictException("BOOKING_CLOSED", "预约已终态（${booking.status}），不在故障补偿范围")
        }
        val now = clock.now()

        val incident = incidentRepo.findByIncidentNo(scan.incidentNo ?: "")
            ?: incidentRepo.findByStoreIdAndTypeAndStatus(
                scan.store.id!!, IncidentType.DOOR_FAILURE, IncidentStatus.OPEN
            ).firstOrNull()
            ?: incidentService.open(
                scan.store.id!!, IncidentType.DOOR_FAILURE,
                "门禁故障维修工单（扫码 ${scan.scanNo} 客服确认）：${scan.errorCode.name}"
            )
        scan.incidentNo = incident.incidentNo

        val waitMinutes = (affectedMinutes ?: Duration.between(scan.scannedAt, now).toMinutes()).coerceAtLeast(0)
        val applied = applyCompensation(
            booking, scan, prefer, waitMinutes,
            fallbackReason = "客服确认门禁故障 ${incident.incidentNo}"
        )

        scan.classification = DoorScanClassification.SUSPECTED_DOOR_FAULT
        scan.status = DoorScanStatus.COMPENSATED
        scan.confirmedBy = staffId
        scan.confirmedAt = now
        scan.updatedAt = now
        if (scan.ticketNo == null) {
            openTicketFor(scan, booking, "DOOR_SCAN", "客服确认门禁故障（${incident.incidentNo}）")
        }
        resolveTicket(scan.ticketNo, Responsibility.STORE,
            "确认门禁故障，${applied.description}${note?.let { "；$it" } ?: ""}", now)
        timeline.record("DOOR_FAULT_CONFIRMED", scan.store.id, booking.bookingNo, staffActor(staffId),
            "${scan.scanNo} 确认故障，维修事件 ${incident.incidentNo}，${applied.description}")

        // 回查：同门店、首次扫码前 30 分钟至当前，是否还有其他用户
        // “持有效预约 + 硬件类失败”（无单/凭证类失败不算整店故障，不纳入补偿）
        val others = scanRepo
            .findByStoreIdAndScannedAtBetweenOrderByScannedAtAsc(
                scan.store.id!!,
                scan.scannedAt.minus(RECHECK_WINDOW_BEFORE), now
            )
            .filter {
                it.id != scan.id && it.user.id != scan.user.id &&
                    it.errorCode.isHardware() &&
                    it.classification == DoorScanClassification.SUSPECTED_DOOR_FAULT &&
                    it.status in listOf(DoorScanStatus.RECORDED, DoorScanStatus.TICKETED) &&
                    (it.bookingNo?.let { no -> bookingRepo.findByBookingNo(no) }?.let { b ->
                        b.status != BookingStatus.CANCELLED && b.status != BookingStatus.NO_SHOW
                    } ?: false)
            }

        val batch = if (others.isNotEmpty()) {
            createStoreWideBatch(incident.incidentNo, scan, others, now)
        } else {
            timeline.record("DOOR_FAULT_SINGLE", scan.store.id, booking.bookingNo, "SYSTEM",
                "${scan.scanNo} 同时段无其他硬件类扫码失败，按单账号故障补偿结案，扫码日志与定位差异保留")
            null
        }
        return FaultConfirmation(scan, batch, batch != null)
    }

    /** 整店批量：统一补偿规则，待客服逐人确认是否实际进入 */
    private fun createStoreWideBatch(
        incidentNo: String,
        first: DoorScanAttempt,
        others: List<DoorScanAttempt>,
        now: Instant
    ): DoorCompensationBatch {
        val windowFrom = (others.map { it.scannedAt } + first.scannedAt).min()
        val batch = batchRepo.save(
            DoorCompensationBatch(
                batchNo = "TMP", store = first.store, incidentNo = incidentNo,
                windowFrom = windowFrom, windowTo = now,
                status = DoorScanStatus.BATCH_PENDING_CONFIRM,
                ruleDescription = "整店门禁异常批量补偿：确认实际进入→按等待时长免费延长使用时间" +
                    "（与后续已支付订单碰撞时改退受影响时段费）；确认未进入→按受影响时段比例退使用费",
                affectedCount = others.size
            )
        )
        batch.batchNo = BizNoGenerator.doorBatch(batch.id!!)

        for (other in others) {
            other.classification = DoorScanClassification.STORE_WIDE_DOOR_FAULT
            other.status = DoorScanStatus.BATCH_PENDING_CONFIRM
            other.batchNo = batch.batchNo
            other.incidentNo = incidentNo
            other.compensationType = CompensationType.PENDING_ENTRY_CONFIRM
            other.updatedAt = now
            val booking = other.bookingNo?.let { bookingRepo.findByBookingNo(it) }
            if (other.ticketNo == null && booking != null) {
                openTicketFor(other, booking, "DOOR_BATCH",
                    "整店门禁异常批量工单（批次 ${batch.batchNo}，事件 $incidentNo），待确认是否实际进入")
            }
            timeline.record("DOOR_FAULT_BATCH_MEMBER", other.store.id, other.bookingNo, "SYSTEM",
                "${other.scanNo} 纳入整店批次 ${batch.batchNo}，待客服确认实际进入")
        }
        timeline.record("DOOR_FAULT_STORE_WIDE", first.store.id, null, "SYSTEM",
            "事件 $incidentNo 判定整店门禁异常，批次 ${batch.batchNo} 覆盖 " +
                "$windowFrom ~ $now，待确认用户 ${others.size} 人")
        return batch
    }

    // ---------------- 整店批量：逐人确认是否实际进入 ----------------

    /**
     * 客服对批量事件中的单个用户确认是否实际进入。
     * - 实际进入：免费延长等待时长（碰撞时回退为退受影响时段费）；
     * - 未进入：按受影响时段比例退使用费。
     */
    @Transactional
    fun confirmBatchEntry(
        scanNo: String,
        actuallyEntered: Boolean,
        enteredAt: Instant? = null,
        staffId: Long? = null,
        note: String? = null
    ): DoorScanAttempt {
        val scan = scanRepo.findByScanNo(scanNo) ?: throw NotFoundException("扫码记录不存在")
        if (scan.status != DoorScanStatus.BATCH_PENDING_CONFIRM) {
            throw ConflictException("NOT_BATCH_PENDING", "该记录不在批量待确认状态（${scan.status}）")
        }
        val booking = bookingRepo.findByBookingNo(scan.bookingNo ?: "")
            ?: throw BizException("NO_BOOKING", "扫码记录无关联预约")
        val now = clock.now()
        val batch = batchRepo.findByBatchNo(scan.batchNo!!)
            ?: throw IllegalStateException("批次 ${scan.batchNo} 丢失")

        if (actuallyEntered) {
            val enterMoment = (enteredAt ?: now).coerceIn(scan.scannedAt, now)
            val waitMinutes = Duration.between(scan.scannedAt, enterMoment).toMinutes().coerceAtLeast(0)
            val applied = applyCompensation(
                booking, scan, CompensationPreference.EXTEND, waitMinutes,
                fallbackReason = "整店事件 ${batch.incidentNo}，确认实际进入"
            )
            scan.status = DoorScanStatus.BATCH_CONFIRMED_ENTERED
            scan.actuallyEntered = true
            scan.confirmedBy = staffId
            scan.confirmedAt = now
            scan.replyNote = "已确认实际进入：${applied.description}"
            resolveTicket(scan.ticketNo, Responsibility.STORE,
                "整店门禁异常，确认进入，${applied.description}${note?.let { "；$it" } ?: ""}", now)
            timeline.record("DOOR_BATCH_ENTERED", scan.store.id, booking.bookingNo, staffActor(staffId),
                "${scan.scanNo} 确认实际进入，${applied.description}")
            batch.confirmedCount += 1
        } else {
            val refund = refundAffectedPeriod(
                booking, scan,
                from = maxOf(scan.scannedAt, booking.startTime),
                reason = "整店事件 ${batch.incidentNo}，确认未实际进入"
            )
            scan.status = DoorScanStatus.BATCH_REFUNDED_NOT_ENTERED
            scan.actuallyEntered = false
            scan.confirmedBy = staffId
            scan.confirmedAt = now
            scan.replyNote = "已确认未实际进入，退还受影响时段费用 ${refund.amount} 元"
            resolveTicket(scan.ticketNo, Responsibility.STORE,
                "整店门禁异常，确认未进入，退受影响时段费 ${refund.amount} 元${note?.let { "；$it" } ?: ""}", now)
            timeline.record("DOOR_BATCH_NOT_ENTERED", scan.store.id, booking.bookingNo, staffActor(staffId),
                "${scan.scanNo} 确认未进入，退款 ${refund.amount}（${refund.refundNo}）")
            batch.refundedCount += 1
        }
        scan.updatedAt = now
        if (batch.confirmedCount + batch.refundedCount >= batch.affectedCount) {
            batch.status = DoorScanStatus.BATCH_RESOLVED
            batch.resolvedAt = now
            timeline.record("DOOR_BATCH_RESOLVED", scan.store.id, null, "SYSTEM",
                "${batch.batchNo} 全部 ${batch.affectedCount} 人确认完毕：进入 ${batch.confirmedCount}，" +
                    "退费 ${batch.refundedCount}")
        }
        return scan
    }

    // ---------------- 用户侧原因：引用订单与定位证据回复 ----------------

    /**
     * 客服对“预约未到 / 走错门店 / 凭证或无单”类扫码记录回复。
     * 回复内容自动引用订单号、预约门店与时间窗、支付状态，以及扫码定位与门店的距离差。
     */
    @Transactional
    fun replyWithEvidence(scanNo: String, note: String, staffId: Long? = null): DoorScanAttempt {
        val scan = scanRepo.findByScanNo(scanNo) ?: throw NotFoundException("扫码记录不存在")
        if (scan.status in TERMINAL_STATUSES) {
            throw ConflictException("SCAN_DONE", "该扫码记录已完结")
        }
        if (scan.status == DoorScanStatus.BATCH_PENDING_CONFIRM) {
            throw ConflictException("USE_BATCH_CONFIRM", "整店批量事件请使用逐人确认接口")
        }
        val now = clock.now()
        val evidence = scan.detail ?: ""
        scan.replyNote = "【证据引用】$evidence。【客服回复】$note"
        scan.status = when (scan.classification) {
            DoorScanClassification.SINGLE_ACCOUNT -> DoorScanStatus.CLOSED_SINGLE_ACCOUNT
            else -> DoorScanStatus.REJECTED_USER_SIDE
        }
        scan.confirmedBy = staffId
        scan.confirmedAt = now
        scan.updatedAt = now
        resolveTicket(
            scan.ticketNo, Responsibility.USER,
            "用户侧原因（${scan.classification.name}）：$note；证据：$evidence", now
        )
        timeline.record("DOOR_SCAN_REPLY", scan.store.id, scan.bookingNo, staffActor(staffId),
            "${scan.scanNo} ${scan.classification.name} 回复：$note")
        return scan
    }

    // ---------------- 补偿执行原语 ----------------

    private data class CompensationApplied(val description: String)

    /**
     * 免费延长优先；延长后会与同座位后续已支付订单碰撞时，自动回退为退受影响时段费。
     * 包月权益只做权益期延长，不按比例退费。
     */
    private fun applyCompensation(
        booking: Booking,
        scan: DoorScanAttempt,
        prefer: CompensationPreference,
        waitMinutes: Long,
        fallbackReason: String
    ): CompensationApplied {
        if (waitMinutes <= 0) {
            scan.compensationType = CompensationType.NONE
            return CompensationApplied("受影响时长为 0，无需补偿")
        }

        if (prefer == CompensationPreference.EXTEND) {
            if (booking.type == BookingType.MONTHLY) {
                return CompensationApplied(extendBooking(booking, waitMinutes, scan))
            }
            val collision = detectCollision(booking, waitMinutes)
            if (collision == null) {
                return CompensationApplied(extendBooking(booking, waitMinutes, scan))
            }
            timeline.record("DOOR_COMP_EXTENSION_COLLISION", booking.store.id, booking.bookingNo, "SYSTEM",
                "免费延长将与后续订单 $collision 碰撞，自动回退为退受影响时段费用")
        }

        if (booking.type == BookingType.MONTHLY) {
            // 包月为周期一口价，无按比例退费口径：即使偏好退费也仅延长权益
            return CompensationApplied(extendBooking(booking, waitMinutes, scan))
        }
        val refund = refundAffectedPeriod(
            booking, scan,
            from = maxOf(scan.scannedAt, booking.startTime),
            minutesCap = waitMinutes,
            reason = fallbackReason
        )
        return CompensationApplied("退还受影响时段费用 ${refund.amount} 元（${refund.refundNo}）")
    }

    /** 免费延长：结束时间后移 wait 分钟、迟到宽限同步延长；在场订单刷新 Redis 占用 TTL */
    private fun extendBooking(booking: Booking, waitMinutes: Long, scan: DoorScanAttempt): String {
        val base = booking.compEndTime ?: booking.effectiveEndTime
        val newEnd = base.plus(Duration.ofMinutes(waitMinutes))
        booking.compEndTime = newEnd
        booking.compGraceMinutes = booking.compGraceMinutes + waitMinutes
        scan.compensationType = CompensationType.FREE_EXTENSION
        scan.compensationMinutes = scan.compensationMinutes + waitMinutes

        if (booking.status == BookingStatus.IN_PROGRESS) {
            booking.seat?.let { seat ->
                val ttl = Duration.between(clock.now(), newEnd).plusHours(2)
                if (!ttl.isNegative && !ttl.isZero) {
                    seatLocks.markOccupied(seat.id!!, booking.bookingNo, ttl)
                }
                sensorRepo.findBySeatId(seat.id!!)?.let {
                    it.occupied = true; it.occupiedByBookingNo = booking.bookingNo; it.updatedAt = clock.now()
                }
            }
        }
        timeline.record("DOOR_COMP_FREE_EXTENSION", booking.store.id, booking.bookingNo, "SYSTEM",
            "门禁故障免费延长 $waitMinutes 分钟，使用截止 $newEnd（不计延时费），迟到宽限同步延长")
        return "免费延长 $waitMinutes 分钟（使用截止 $newEnd，免收延时费）"
    }

    /** 延长后是否侵占同座位后续已支付/在场订单；返回碰撞订单号 */
    private fun detectCollision(booking: Booking, waitMinutes: Long): String? {
        val seat = booking.seat ?: return null
        val currentEnd = booking.effectiveEndTime
        val newEnd = currentEnd.plus(Duration.ofMinutes(waitMinutes))
        return bookingRepo.findActiveOnSeat(seat.id!!, currentEnd)
            .firstOrNull { it.id != booking.id && it.startTime.isBefore(newEnd) }
            ?.bookingNo
    }

    /**
     * 退受影响时段使用费：按受影响分钟占预约总分钟比例、门店责任全额比例（1.0）退。
     * @param from 受影响时段起点
     * @param minutesCap 受影响分钟上限（等待时长）；null 表示从 from 退到预约结束
     */
    private fun refundAffectedPeriod(
        booking: Booking,
        scan: DoorScanAttempt,
        from: Instant,
        minutesCap: Long? = null,
        reason: String
    ): Refund {
        val totalMinutes = Duration.between(booking.startTime, booking.endTime).toMinutes().coerceAtLeast(1)
        val end = booking.effectiveEndTime
        var affected = Duration.between(from, end).toMinutes().coerceIn(0, totalMinutes)
        if (minutesCap != null) affected = affected.coerceAtMost(minutesCap)
        if (affected <= 0) {
            scan.compensationType = CompensationType.NONE
            throw BizException("NO_AFFECTED_PERIOD", "受影响时段为 0，无费用可退（可改用免费延长）")
        }
        val amount = booking.price
            .multiply(BigDecimal.valueOf(affected.toDouble() / totalMinutes))
            .setScale(2, RoundingMode.HALF_UP)
        val refund = billing.refund(
            booking, RefundReason.STORE_FAULT, amount,
            incidentNo = scan.incidentNo, ticketNo = scan.ticketNo,
            note = "门禁故障受影响时段 $affected 分钟（$reason）"
        )
        scan.compensationType = CompensationType.AFFECTED_PERIOD_REFUND
        scan.refundNo = refund.refundNo
        scan.refundAmount = refund.amount
        return refund
    }

    // ---------------- 证据/工单/分类辅助 ----------------

    private fun openTicketFor(
        scan: DoorScanAttempt, booking: Booking, source: String, title: String,
        cause: DisputeCause = DisputeCause.DEVICE_FAULT
    ) {
        val t = ticketRepo.save(
            SupportTicket(
                ticketNo = "TMP", store = scan.store, user = scan.user,
                bookingNo = booking.bookingNo, cause = cause,
                status = TicketStatus.OPEN, title = title.take(200),
                description = (scan.detail ?: title),
                incidentNo = scan.incidentNo,
                monitorEventNos = scan.monitorEventNo?.let { listOf(it).joinToString() },
                source = source
            )
        )
        t.ticketNo = BizNoGenerator.ticket(t.id!!)
        scan.ticketNo = t.ticketNo
    }

    private fun resolveTicket(ticketNo: String?, responsibility: Responsibility, note: String, now: Instant) {
        if (ticketNo == null) return
        val t = ticketRepo.findByTicketNo(ticketNo) ?: return
        if (t.status == TicketStatus.CLOSED || t.status == TicketStatus.RESOLVED) return
        t.responsibility = responsibility
        t.staffNote = note.take(1000)
        t.status = TicketStatus.RESOLVED
        t.resolvedAt = now
        t.updatedAt = now
    }

    private fun attachToStoreWideBatch(
        scan: DoorScanAttempt,
        openIncident: Incident?,
        openBatch: DoorCompensationBatch?,
        now: Instant
    ) {
        // 优先复用在途维修事件；否则复用批次关联事件（若已结案则按复发重新打开）；都没有才新建
        var incident = openIncident
            ?: openBatch?.let { incidentRepo.findByIncidentNo(it.incidentNo) }
        if (incident != null && incident.status == IncidentStatus.RESOLVED) {
            incident.status = IncidentStatus.OPEN
            incident.resolvedAt = null
            incident.resolution = null
            timeline.record("INCIDENT_REOPENED", incident.store.id, null, "SYSTEM",
                "${incident.incidentNo} 再次出现扫码失败 ${scan.scanNo}，维修工单重新打开")
        }
        if (incident == null) {
            incident = incidentService.open(
                scan.store.id!!, IncidentType.DOOR_FAILURE,
                "门禁故障维修工单（批次自动升级，扫码 ${scan.scanNo}）"
            )
        }
        scan.incidentNo = incident.incidentNo
        val booking = scan.bookingNo?.let { bookingRepo.findByBookingNo(it) }
        val batch = openBatch ?: batchRepo.save(
            DoorCompensationBatch(
                batchNo = "TMP", store = scan.store, incidentNo = incident.incidentNo,
                windowFrom = scan.scannedAt, windowTo = now,
                status = DoorScanStatus.BATCH_PENDING_CONFIRM,
                ruleDescription = "整店门禁异常批量补偿：确认实际进入→免费延长等待时长（碰撞改退费）；确认未进入→退受影响时段费用"
            )
        ).also {
            it.batchNo = BizNoGenerator.doorBatch(it.id!!)
        }
        if (openBatch == null) batch.windowTo = now
        scan.classification = DoorScanClassification.STORE_WIDE_DOOR_FAULT
        scan.status = DoorScanStatus.BATCH_PENDING_CONFIRM
        scan.batchNo = batch.batchNo
        scan.compensationType = CompensationType.PENDING_ENTRY_CONFIRM
        scan.updatedAt = now
        if (booking != null) {
            openTicketFor(scan, booking, "DOOR_BATCH",
                "整店门禁异常批量工单（批次 ${batch.batchNo}，事件 ${incident.incidentNo}），待确认是否实际进入")
        }
        batch.affectedCount += 1
        timeline.record("DOOR_FAULT_BATCH_MEMBER", scan.store.id, booking?.bookingNo, "SYSTEM",
            "${scan.scanNo} 直接并入在途整店批次 ${batch.batchNo}，待确认实际进入")
    }

    private fun buildEvidenceDetail(
        classification: DoorScanClassification,
        errorCode: DoorErrorCode,
        matched: Booking?,
        activeOtherStore: Booking?,
        store: Store,
        scannedAt: Instant,
        distance: Double?
    ): String = buildString {
        append("错误码 ").append(errorCode.name)
        append("，扫码时间 ").append(scannedAt)
        if (matched != null) {
            append("，订单 ").append(matched.bookingNo)
            append("（").append(matched.status.name).append("）")
            append("，预约门店=").append(matched.store.name)
            append("，预约时段 ").append(matched.startTime).append("~").append(matched.endTime)
        }
        if (activeOtherStore != null) {
            append("，有效订单 ").append(activeOtherStore.bookingNo)
            append(" 属于其他门店「").append(activeOtherStore.store.name).append("」")
        }
        if (matched == null && activeOtherStore == null) append("，无有效预约/权益")
        append("，扫码门店=").append(store.name)
        if (distance != null) append("，定位距扫码门店 ").append("%.0f".format(distance)).append(" 米")
        append("，初判 ").append(classification.name)
    }.take(900)

    private fun accessResultFor(c: DoorScanClassification, code: DoorErrorCode): AccessResult = when (c) {
        DoorScanClassification.SUSPECTED_DOOR_FAULT,
        DoorScanClassification.STORE_WIDE_DOOR_FAULT -> AccessResult.DENIED_DOOR_FAULT
        DoorScanClassification.BOOKING_NOT_STARTED -> AccessResult.DENIED_OUTSIDE_WINDOW
        DoorScanClassification.WRONG_STORE -> AccessResult.DENIED_WRONG_STORE
        DoorScanClassification.STORE_BLACKOUT -> AccessResult.DENIED_STORE_BLACKOUT
        DoorScanClassification.SINGLE_ACCOUNT ->
            if (code == DoorErrorCode.INVALID_QRCODE) AccessResult.DENIED_QR_INVALID else AccessResult.DENIED_NO_BOOKING
        DoorScanClassification.NO_BOOKING -> AccessResult.DENIED_NO_BOOKING
    }

    private fun staffActor(staffId: Long?) = staffId?.let { "STAFF:$it" } ?: "STAFF"

    companion object {
        /** 整店回查窗口：首次扫码前 30 分钟 */
        val RECHECK_WINDOW_BEFORE: Duration = Duration.ofMinutes(30)

        val TERMINAL_STATUSES = setOf(
            DoorScanStatus.COMPENSATED,
            DoorScanStatus.BATCH_CONFIRMED_ENTERED,
            DoorScanStatus.BATCH_REFUNDED_NOT_ENTERED,
            DoorScanStatus.BATCH_RESOLVED,
            DoorScanStatus.CLOSED_SINGLE_ACCOUNT,
            DoorScanStatus.REJECTED_USER_SIDE
        )

        /** 不允许走“确认门禁故障”自动赔付的初判类型（用户侧原因/无单/停电走其他流程） */
        val USER_SIDE_CLASSIFICATIONS = setOf(
            DoorScanClassification.BOOKING_NOT_STARTED,
            DoorScanClassification.WRONG_STORE,
            DoorScanClassification.SINGLE_ACCOUNT,
            DoorScanClassification.NO_BOOKING,
            DoorScanClassification.STORE_BLACKOUT
        )

        fun DoorErrorCode.isHardware(): Boolean = this in setOf(
            DoorErrorCode.READER_NO_RESPONSE,
            DoorErrorCode.GATE_NOT_OPENING,
            DoorErrorCode.DEVICE_OFFLINE,
            DoorErrorCode.UNKNOWN
        )

        /** 两点球面距离（米），定位证据只需要米级近似 */
        fun haversineMeters(lat1: Double?, lon1: Double?, lat2: Double?, lon2: Double?): Double? {
            if (lat1 == null || lon1 == null || lat2 == null || lon2 == null) return null
            val r = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
            return r * 2 * atan2(sqrt(a), sqrt(1 - a))
        }
    }
}
