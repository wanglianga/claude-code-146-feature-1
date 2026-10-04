package com.studyroom.service

import com.studyroom.domain.*
import com.studyroom.repo.*
import com.studyroom.support.BizException
import com.studyroom.support.NotFoundException
import com.studyroom.support.TimeProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 客服确认门禁故障后的补偿指令 */
data class DoorCompensationCommand(
    val compensationType: CompensationType,
    /** 延长分钟数（EXTEND_TIME 时生效） */
    val minutes: Long = 60,
    /** 是否立即走人工通道放行（EXTEND_TIME 默认放行） */
    val grantManualEntry: Boolean = true,
    val staffNote: String? = null
)

/** 确认故障 + 回查批量补偿的结果 */
data class ConfirmFaultResult(
    val attempt: DoorScanAttempt,
    val incidentNo: String,
    val repairNo: String,
    val manualEntry: AccessService.EntryDecision?,
    val batchEventNo: String?,
    val batchAttemptNos: List<String>
)

/**
 * 门禁打不开的自动补偿。
 *
 * 主链路：
 * 1. [reportScan] 用户在店扫码失败 → 记录扫码时间、门禁错误码、支付状态、定位，系统初判并给出引用订单/定位证据的回复；
 * 2. [confirmFault] 客服（结合监控）确认门禁故障 → 打开/复用设备事件、生成门店维修工单，
 *    延长本次使用时间（人工通道放行）或退还受影响时段费用（未进场全额退、关闭订单）；
 * 3. 补偿完成后回查同一时段是否还有其他用户扫码失败：
 *    去重用户达到阈值判定为“整店门禁异常”，按门店事件批量套用同一补偿规则生成批次 [StoreDoorEvent]，
 *    客服只需 [confirmBatchEntry] 逐个确认是否实际进入；
 * 4. 未到进场时间 / 走错门店 / 单账号异常（未支付、未实名、定位差异过大）不补偿，
 *    扫码日志、错误码、支付快照与定位差异永久保留，防止以“设备故障”名义套全额退款。
 */
@Service
class DoorCompensationService(
    private val attemptRepo: DoorScanAttemptRepository,
    private val doorEventRepo: StoreDoorEventRepository,
    private val repairRepo: RepairTicketRepository,
    private val bookingRepo: BookingRepository,
    private val userRepo: AppUserRepository,
    private val storeRepo: StoreRepository,
    private val incidentRepo: IncidentRepository,
    private val ticketRepo: SupportTicketRepository,
    private val accessLogRepo: AccessLogRepository,
    private val incidentService: IncidentService,
    private val accessService: AccessService,
    private val billing: BillingService,
    private val timeline: TimelineService,
    private val clock: TimeProvider
) {
    // ---------------- 扫码失败上报与初判 ----------------

    @Transactional
    fun reportScan(
        userId: Long,
        storeId: Long,
        errorCode: DoorErrorCode,
        doorId: String? = null,
        clientLat: Double? = null,
        clientLng: Double? = null
    ): DoorScanAttempt {
        val now = clock.now()
        val user = userRepo.findById(userId).orElseThrow { NotFoundException("用户不存在") }
        val store = storeRepo.findById(storeId).orElseThrow { NotFoundException("门店不存在") }

        val distance = distanceMeters(store.latitude, store.longitude, clientLat, clientLng)
        val ctx = classify(user, store, now, distance)

        val monitor = monitorEventNo(store, doorId, ctx.accessResult, now)
        val attempt = attemptRepo.save(
            DoorScanAttempt(
                attemptNo = "TMP",
                user = user, store = store,
                bookingNo = ctx.booking?.bookingNo,
                bookingStoreId = ctx.booking?.store?.id,
                bookingSeatCode = ctx.booking?.seat?.code,
                doorId = doorId, errorCode = errorCode, scannedAt = now,
                clientLat = clientLat, clientLng = clientLng,
                locationDistanceMeters = distance?.let { BigDecimal(it).setScale(1, java.math.RoundingMode.HALF_UP).toDouble() },
                paymentStatus = ctx.paymentStatus,
                bookingStart = ctx.booking?.startTime, bookingEnd = ctx.booking?.let { it.effectiveEndTime },
                classification = ctx.classification,
                suggestedReply = ctx.reply,
                monitorEventNo = monitor
            )
        )
        attempt.attemptNo = BizNoGenerator.doorAttempt(attempt.id!!)

        accessLogRepo.save(
            AccessLog(
                bookingNo = ctx.booking?.bookingNo, user = user, store = store, doorId = doorId,
                direction = AccessDirection.IN, result = ctx.accessResult,
                detail = "扫码失败[$errorCode]：${ctx.reply}",
                monitorEventNo = monitor, createdAt = now
            )
        )
        timeline.record(
            "DOOR_SCAN_DENIED", store.id, ctx.booking?.bookingNo, "USER:${user.id}",
            "${attempt.attemptNo} $errorCode 初判 ${ctx.classification}，支付=${ctx.paymentStatus}" +
                (distance?.let { "，定位距门店 ${it.toInt()}m" } ?: "")
        )
        return attempt
    }

    private data class ScanContext(
        val classification: DoorFailureClassification,
        val accessResult: AccessResult,
        val paymentStatus: String,
        val booking: Booking?,
        val reply: String
    )

    /**
     * 初判规则（均以系统内订单/支付/定位数据为准，不采信用户口述）：
     * 本门店有开窗内已支付预约 → 疑似门禁故障；但定位离店过远 → 降级单账号异常。
     * 他店有有效预约 → 走错门店；当前时段有未支付订单 → 账号异常；
     * 最近的已支付订单在未来 → 提前到店；其余 → 无有效预约。
     */
    private fun classify(user: AppUser, store: Store, now: Instant, distance: Double?): ScanContext {
        val storeId = store.id!!
        val userId = user.id!!

        val here = bookingRepo.findEnterableTimed(userId, storeId, now).firstOrNull()
            ?: bookingRepo.findActiveMembership(storeId, userId, now)
        if (here != null) {
            if (!user.realNameVerified) {
                return ScanContext(
                    DoorFailureClassification.ACCOUNT_NOT_ELIGIBLE, AccessResult.DENIED_NOT_REALNAME, "PAID", here,
                    "您的预约 ${here.bookingNo}（座位 ${here.seat?.code}）已支付，但账号尚未完成实名认证，" +
                        "门禁无法放行；请先完成实名认证后再扫码，本次不属于设备故障。"
                )
            }
            if (distance != null && distance > LOCATION_TOLERANCE_METERS) {
                return ScanContext(
                    DoorFailureClassification.ACCOUNT_NOT_ELIGIBLE, AccessResult.DENIED_NO_BOOKING, "PAID", here,
                    "您的预约 ${here.bookingNo} 虽在进场时段内并已支付，但扫码定位距「${store.name}」约 ${distance.toInt()} 米" +
                        "（超过 ${LOCATION_TOLERANCE_METERS} 米容差），无法证明您已到店；请在门店门禁处重试，" +
                        "或联系客服核对监控。定位与扫码日志已留存，不支持以设备故障名义申请全额退款。"
                )
            }
            return ScanContext(
                DoorFailureClassification.DOOR_FAULT_SUSPECTED, AccessResult.DENIED_DOOR_FAULT, "PAID", here,
                "您的预约 ${here.bookingNo}（座位 ${here.seat?.code}，${here.startTime} ~ ${here.effectiveEndTime}）已支付且在进场时段内，" +
                    "门禁仍无法识别，已按疑似门店门禁故障登记，客服将结合监控核实：若确认故障，为您延长本次使用时间或退还受影响时段费用。"
            )
        }

        // 他店当前可进入的预约/权益 → 走错门店
        val otherTimed = bookingRepo.findEnterableTimedAnyStore(userId, now).firstOrNull { it.store.id != storeId }
        val otherMembership = bookingRepo.findActiveMembershipsAnyStore(userId, now).firstOrNull { it.store.id != storeId }
        val other = otherTimed ?: otherMembership
        if (other != null) {
            val otherStore = other.store
            return ScanContext(
                DoorFailureClassification.WRONG_STORE, AccessResult.DENIED_NO_BOOKING, "PAID", other,
                "您当前扫码的是「${store.name}」，但当前有效${if (other.type == BookingType.MONTHLY) "包月权益" else "预约"}" +
                    " ${other.bookingNo} 属于「${otherStore.name}」（${otherStore.address}），座位 ${other.seat?.code}；" +
                    (distance?.let { "本次扫码定位距当前门店约 ${it.toInt()} 米。" } ?: "") +
                    "请前往预约门店入场，该订单与定位证据已留存，本次不构成门禁故障补偿。"
            )
        }

        // 当前时段有未支付订单
        val allMine = bookingRepo.findByUserIdOrderByStartTimeDesc(userId)
        val unpaidOverlap = allMine.firstOrNull { b ->
            b.type != BookingType.MONTHLY && b.status == BookingStatus.PENDING_PAYMENT &&
                b.startTime <= now && b.effectiveEndTime > now
        }
        if (unpaidOverlap != null) {
            return ScanContext(
                DoorFailureClassification.ACCOUNT_NOT_ELIGIBLE, AccessResult.DENIED_UNPAID, "UNPAID", unpaidOverlap,
                "您的预约 ${unpaidOverlap.bookingNo}（${unpaidOverlap.startTime} ~ ${unpaidOverlap.effectiveEndTime}）尚未支付，" +
                    "支付状态 UNPAID，门禁不予放行；请先完成支付再扫码。"
            )
        }

        // 最近 24h 内未来的订单 → 提前到店（回复引用订单时间与门店）
        val horizon = now.plus(Duration.ofHours(24))
        val upcoming = allMine.firstOrNull { b ->
            b.type != BookingType.MONTHLY &&
                b.status in listOf(BookingStatus.CONFIRMED, BookingStatus.PENDING_PAYMENT) &&
                b.startTime > now && b.startTime <= horizon
        }
        if (upcoming != null) {
            val pay = if (upcoming.status == BookingStatus.CONFIRMED) "PAID" else "UNPAID"
            val sameStore = upcoming.store.id == storeId
            return ScanContext(
                DoorFailureClassification.EARLY_ARRIVAL, AccessResult.DENIED_OUTSIDE_WINDOW, pay, upcoming,
                buildString {
                    append("您的预约 ${upcoming.bookingNo} 开始时间为 ${upcoming.startTime}（门店「${upcoming.store.name}」" +
                        "允许提前 ${upcoming.store.earlyEntryMinutes} 分钟进场），本次扫码时间 $now 尚未到进场时段。")
                    if (!sameStore) append("且该预约不在当前扫码门店「${store.name}」，请确认前往门店。")
                    if (pay == "UNPAID") append("订单尚未支付，请先完成支付。")
                    append("订单时间、门店与本次扫码记录已留存。")
                }
            )
        }

        return ScanContext(
            DoorFailureClassification.ACCOUNT_NOT_ELIGIBLE, AccessResult.DENIED_NO_BOOKING, "NONE", null,
            "未查询到您在当前时段有效且已支付的预约或包月权益（支付状态 NONE）。" +
                (distance?.let { "扫码定位距「${store.name}」约 ${it.toInt()} 米。" } ?: "") +
                "请确认账号与订单后再试；如需申诉请联系客服核对监控。"
        )
    }

    // ---------------- 客服确认门禁故障 → 补偿 + 维修工单 + 回查 ----------------

    @Transactional
    fun confirmFault(attemptNo: String, cmd: DoorCompensationCommand): ConfirmFaultResult {
        val attempt = attemptRepo.findByAttemptNo(attemptNo) ?: throw NotFoundException("扫码记录不存在")
        if (attempt.status != DoorAttemptStatus.PENDING) {
            throw BizException("ATTEMPT_HANDLED", "扫码记录 ${attempt.attemptNo} 状态为 ${attempt.status}，已处理")
        }
        if (attempt.classification != DoorFailureClassification.DOOR_FAULT_SUSPECTED) {
            throw BizException("NOT_DOOR_FAULT", "初判为 ${attempt.classification}，不属于门禁故障，请使用驳回并依据证据回复用户")
        }
        if (cmd.compensationType == CompensationType.EXTEND_TIME && cmd.minutes <= 0) {
            throw BizException("BAD_MINUTES", "延长分钟数必须大于 0")
        }
        val now = clock.now()
        val store = attempt.store
        val booking = attempt.bookingNo?.let { bookingRepo.findByBookingNo(it) }
            ?: throw BizException("BOOKING_MISSING", "扫码记录缺少有效预约，无法补偿")

        if (booking.type == BookingType.MONTHLY && cmd.compensationType == CompensationType.REFUND_FEE) {
            throw BizException("MONTHLY_NO_PARTIAL_REFUND", "包月权益不按单次门禁故障退费，请改用延长权益并经人工通道放行")
        }

        // 1) 打开/复用门店门禁故障事件（事件 OPEN 后正常门禁自动拒绝，人工通道不受影响）
        val incident = incidentRepo.findByStoreIdAndTypeAndStatus(
            store.id!!, IncidentType.DOOR_FAILURE, IncidentStatus.OPEN
        ).firstOrNull() ?: incidentService.open(
            store.id!!, IncidentType.DOOR_FAILURE,
            "门禁无法识别/打不开（闸机 ${attempt.doorId ?: "MAIN"}，错误码 ${attempt.errorCode}），由扫码失败补偿自动立案"
        )
        // 2) 门店维修工单（同一设备事件只生成一张）
        val repair = repairRepo.findByIncidentNo(incident.incidentNo).firstOrNull()
            ?: openRepairTicket(store, incident.incidentNo, attempt.doorId, attempt.errorCode)
        attempt.incidentNo = incident.incidentNo

        // 客服介入留痕：生成客服工单并随补偿结论一并结案（证据包可按单号回放）
        val ticket = openSupportTicket(
            attempt, booking, DisputeCause.DEVICE_FAULT,
            title = "[门禁故障] ${attempt.errorCode} @ ${attempt.doorId ?: "MAIN"}",
            description = "用户 ${attempt.user.name}(ID:${attempt.user.id}) 到店扫码无法进入；错误码 ${attempt.errorCode}，" +
                "支付状态 ${attempt.paymentStatus}，扫码时间 ${attempt.scannedAt}" +
                    (attempt.locationDistanceMeters?.let { "，定位距门店 ${it.toInt()}m" } ?: "")
        )
        ticket?.let {
            it.responsibility = Responsibility.STORE
            it.status = TicketStatus.RESOLVED
            it.resolvedAt = now
            it.staffNote = cmd.staffNote ?: "确认门店门禁故障，按 ${cmd.compensationType} 补偿"
            attempt.supportTicketNo = it.ticketNo
        }

        // 3) 执行补偿（锚定用户：EXTEND 立即人工通道放行，REFUND 当场全额退）
        val manualEntry = applyCompensation(attempt, booking, cmd, incident.incidentNo, now)
        attempt.status = DoorAttemptStatus.CONFIRMED_FAULT
        attempt.staffNote = cmd.staffNote
        attempt.resolvedAt = now
        attempt.updatedAt = now

        val compText = if (cmd.compensationType == CompensationType.EXTEND_TIME)
            "${cmd.compensationType} ${cmd.minutes} 分钟" else cmd.compensationType.name
        timeline.record(
            "DOOR_FAULT_CONFIRMED", store.id, booking.bookingNo, "STAFF",
            "${attempt.attemptNo} 确认门禁故障（事件 ${incident.incidentNo}，维修单 ${repair.repairNo}）：$compText"
        )

        // 4) 补偿完成后回查同一时段其他扫码失败用户，按门店事件批量补偿
        val review = reviewAndBatch(incident.incidentNo, attempt, booking, cmd, now)

        return ConfirmFaultResult(
            attempt, incident.incidentNo, repair.repairNo, manualEntry,
            review.event?.eventNo, review.peerAttemptNos
        )
    }

    private data class BatchReview(val event: StoreDoorEvent?, val peerAttemptNos: List<String>)

    /**
     * 回查同一门店、同一时段（前后 [WINDOW_MINUTES] 分钟）其他用户的疑似门禁故障扫码记录。
     * 去重用户数达到 [BATCH_USER_THRESHOLD] 即判定整店门禁异常，按门店事件批量生成补偿；
     * 不足阈值则维持逐单处理，扫码日志与定位差异全部保留。
     *
     * 回查对象包括：
     * - 本次锚定用户（已当场完成补偿，是否进入为已知事实）；
     * - 同窗内更早被逐单确认补偿的用户（故障系同一设备事件，批次成立时回溯纳入统计，不重复补偿）；
     * - 同窗内仍待处理的用户（批量套用同一补偿规则，置 BATCH_COMPENSATED，等待客服逐个核实）。
     */
    private fun reviewAndBatch(
        incidentNo: String,
        anchor: DoorScanAttempt,
        anchorBooking: Booking,
        cmd: DoorCompensationCommand,
        now: Instant
    ): BatchReview {
        val storeId = anchor.store.id!!
        val from = anchor.scannedAt.minus(Duration.ofMinutes(WINDOW_MINUTES))
        val to = anchor.scannedAt.plus(Duration.ofMinutes(WINDOW_MINUTES))
        val inWindow = attemptRepo.findByStoreIdAndScannedAtBetweenOrderByScannedAtAsc(storeId, from, to)
            .filter {
                it.classification == DoorFailureClassification.DOOR_FAULT_SUSPECTED &&
                    it.status in setOf(DoorAttemptStatus.PENDING, DoorAttemptStatus.CONFIRMED_FAULT)
            }

        // 同一用户多次扫码只保留最近一条
        val latestByUser = inWindow.groupBy { it.user.id }
            .mapValues { (_, list) -> list.maxBy { it.scannedAt } }
            .values.sortedBy { it.scannedAt }
        val affectedUsers = latestByUser.map { it.user.id }.distinct()
        val existing = doorEventRepo.findByIncidentNo(incidentNo)

        if (existing == null && affectedUsers.size < BATCH_USER_THRESHOLD) {
            timeline.record(
                "DOOR_REVIEW_SINGLE_ACCOUNT", storeId, anchorBooking.bookingNo, "SYSTEM",
                "${anchor.attemptNo} 补偿完成后回查 [$from ~ $to]：累计 ${affectedUsers.size} 名用户扫码失败，" +
                    "不构成整店门禁异常；其余扫码日志、错误码、支付快照、定位差异保留备查，防止冒领全额退款"
            )
            return BatchReview(null, emptyList())
        }

        val event = existing ?: createBatchEvent(incidentNo, anchor, latestByUser, cmd)
        // 已逐单确认补偿但尚未归入批次的用户（含本次锚定者）：按已知事实回溯计入统计，不重复补偿
        val toBackfill = latestByUser.filter {
            it.status == DoorAttemptStatus.CONFIRMED_FAULT && it.batchNo == null
        }
        for (victim in toBackfill) {
            val b = victim.bookingNo?.let { bookingRepo.findByBookingNo(it) }
            val entered = victim.compensationType == CompensationType.EXTEND_TIME &&
                b?.status == BookingStatus.IN_PROGRESS
            event.totalAttempts += 1
            if (entered) event.enteredCount += 1 else event.notEnteredCount += 1
            victim.batchNo = event.eventNo
            victim.updatedAt = now
            if (victim.id != anchor.id || existing != null) {
                timeline.record(
                    "DOOR_BATCH_BACKFILL", storeId, victim.bookingNo, "SYSTEM",
                    "${victim.attemptNo} 已逐单补偿，纳入门店事件批次 ${event.eventNo}（不重复补偿）"
                )
            }
        }

        // 仍待处理的用户：批量套用规则（只延长窗口/退费，不替用户自动放行）
        val peers = latestByUser.filter {
            it.id != anchor.id && it.status == DoorAttemptStatus.PENDING && it.batchNo == null
        }
        val batchCmd = cmd.copy(grantManualEntry = false)
        val compensated = mutableListOf<String>()
        for (peer in peers) {
            val peerBooking = peer.bookingNo?.let { bookingRepo.findByBookingNo(it) }
            var note = ""
            if (peerBooking == null) {
                note = "缺少有效预约，批量补偿跳过，待客服核实"
            } else if (peerBooking.type == BookingType.MONTHLY && cmd.compensationType == CompensationType.REFUND_FEE) {
                note = "包月权益不按单次退费，批量退费跳过，建议人工放行"
            } else {
                runCatching { applyCompensation(peer, peerBooking, batchCmd, incidentNo, now) }
                    .onFailure { e -> note = "批量自动补偿未完成：${e.message}，待客服处理" }
            }
            peer.batchNo = event.eventNo
            peer.incidentNo = incidentNo
            peer.status = DoorAttemptStatus.BATCH_COMPENSATED
            peer.staffNote = note.ifBlank { null }
            peer.updatedAt = now
            event.totalAttempts += 1
            compensated.add(peer.attemptNo)
            timeline.record(
                "DOOR_BATCH_COMPENSATED", storeId, peer.bookingNo, "SYSTEM",
                "${peer.attemptNo} 纳入门店事件批次 ${event.eventNo}，规则 ${cmd.compensationType}" +
                    if (note.isNotBlank()) "；$note" else ""
            )
        }
        if (peers.isEmpty() && existing != null) {
            timeline.record(
                "DOOR_EVENT_BATCH_NO_NEW_PEER", storeId, null, "SYSTEM",
                "${anchor.attemptNo} 回查时段内无新的待处理扫码失败用户，仍归入既有批次 ${event.eventNo}"
            )
        }
        return BatchReview(event, compensated)
    }

    private fun createBatchEvent(
        incidentNo: String, anchor: DoorScanAttempt, victims: List<DoorScanAttempt>, cmd: DoorCompensationCommand
    ): StoreDoorEvent {
        val allTimes = victims.map { it.scannedAt }
        val event = doorEventRepo.save(
            StoreDoorEvent(
                eventNo = "TMP", store = anchor.store, incidentNo = incidentNo,
                doorId = anchor.doorId,
                windowFrom = allTimes.minOrNull()!!, windowTo = allTimes.maxOrNull()!!,
                compensationType = cmd.compensationType,
                compensationMinutes = if (cmd.compensationType == CompensationType.EXTEND_TIME) cmd.minutes else 0L,
                description = "整店门禁异常（错误码 ${anchor.errorCode}，闸机 ${anchor.doorId ?: "MAIN"}）：" +
                    "回查同一时段 ${allTimes.size} 名用户扫码失败，按门店事件批量" +
                    if (cmd.compensationType == CompensationType.EXTEND_TIME) "延长 ${cmd.minutes} 分钟" else "退费",
                repairTicketNo = repairRepo.findByIncidentNo(incidentNo).firstOrNull()?.repairNo,
                openedAt = clock.now()
            )
        )
        event.eventNo = BizNoGenerator.doorEvent(event.id!!)
        event.totalAttempts = 0
        timeline.record(
            "DOOR_EVENT_BATCH_OPENED", anchor.store.id, null, "SYSTEM",
            "${event.eventNo} 关联事件 $incidentNo，影响时段 ${event.windowFrom} ~ ${event.windowTo}，" +
                "规则 ${event.compensationType}；客服只需逐个确认用户是否实际进入"
        )
        return event
    }

    /** 对单条扫码记录执行补偿；EXTEND_TIME 默认人工通道放行，REFUND_FEE 仅用于未进场订单 */
    private fun applyCompensation(
        attempt: DoorScanAttempt, booking: Booking, cmd: DoorCompensationCommand, incidentNo: String, now: Instant
    ): AccessService.EntryDecision? {
        attempt.compensationType = cmd.compensationType
        return when (cmd.compensationType) {
            CompensationType.EXTEND_TIME -> {
                // 先延长结束时间，再放行：保证 Redis 占用 TTL 按延长后的窗口计算
                val newEnd = booking.effectiveEndTime.plus(Duration.ofMinutes(cmd.minutes))
                booking.extendedEndTime = newEnd
                booking.extended = true
                attempt.compensationMinutes = cmd.minutes
                timeline.record(
                    "DOOR_FAULT_TIME_EXTENDED", booking.store.id, booking.bookingNo, "SYSTEM",
                    "门禁故障补偿：使用时间延长 ${cmd.minutes} 分钟至 $newEnd（免收延时费）"
                )
                recordCollisionWarnings(booking, now)
                if (cmd.grantManualEntry) grantManualEntry(attempt, booking, incidentNo, now, cmd.minutes) else null
            }
            CompensationType.REFUND_FEE -> {
                if (booking.actualCheckIn != null || booking.status == BookingStatus.IN_PROGRESS) {
                    throw BizException("ALREADY_ENTERED", "订单 ${booking.bookingNo} 已有进场记录，不能按未进入全额退；请改用延长或按工单部分退")
                }
                val refund = applyRefundForAffectedPeriod(booking, incidentNo, attempt.attemptNo, now)
                attempt.compensatedAmount = refund
                timeline.record(
                    "DOOR_FAULT_REFUNDED", booking.store.id, booking.bookingNo, "STAFF",
                    "门禁故障未进场：受影响时段全额退 $refund 元，订单关闭"
                )
                null
            }
        }
    }

    /** 人工通道放行：复用进场的座位锁/换座/占用一致性，仅跳过门禁设备与时间窗判定 */
    private fun grantManualEntry(
        attempt: DoorScanAttempt, booking: Booking, incidentNo: String, now: Instant, minutes: Long
    ): AccessService.EntryDecision? {
        if (booking.status != BookingStatus.CONFIRMED) return null
        val decision = accessService.enterManually(
            booking, attempt.user, attempt.store, attempt.doorId, now,
            "门禁故障（事件 $incidentNo）客服确认，人工通道放行并补偿延长 $minutes 分钟"
        )
        timeline.record(
            "DOOR_FAULT_MANUAL_ENTRY", booking.store.id, booking.bookingNo, "STAFF",
            "${attempt.attemptNo} 人工通道放行：${decision.result} 座位 ${decision.seatCode}"
        )
        return decision
    }

    /** 受影响时段退费：未进场即整个预约时段未使用，全额退使用费+押金并关闭订单（封顶保护仍生效） */
    private fun applyRefundForAffectedPeriod(
        booking: Booking, incidentNo: String, attemptNo: String, now: Instant
    ): BigDecimal {
        val want = booking.price.add(booking.deposit)
        val refund = billing.refund(
            booking, RefundReason.STORE_FAULT, want,
            incidentNo = incidentNo,
            note = "门禁故障未进场（扫码记录 $attemptNo），受影响时段全额退"
        )
        booking.status = BookingStatus.COMPLETED
        booking.relatedIncidentNo = incidentNo
        booking.actualCheckOut = null
        return refund.amount
    }

    /** 延长可能侵占后续座位：记录碰撞预警，下一位进场时既有自动换座逻辑会处理 */
    private fun recordCollisionWarnings(booking: Booking, now: Instant) {
        val seat = booking.seat ?: return
        val blockers = bookingRepo.findActiveOnSeat(seat.id!!, now)
            .filter { it.id != booking.id && it.startTime < booking.effectiveEndTime }
        blockers.forEach { next ->
            timeline.record(
                "COLLISION_PREDICTED", booking.store.id, next.bookingNo, "SYSTEM",
                "订单 ${booking.bookingNo} 因门禁故障补偿延长至 ${booking.effectiveEndTime}，" +
                    "可能占用本订单座位 ${seat.code}，进场时自动处理换座"
            )
        }
    }

    // ---------------- 客服驳回：未到时间 / 走错门店 / 单账号异常 ----------------

    @Transactional
    fun reject(attemptNo: String, note: String): DoorScanAttempt {
        val attempt = attemptRepo.findByAttemptNo(attemptNo) ?: throw NotFoundException("扫码记录不存在")
        if (attempt.status != DoorAttemptStatus.PENDING) {
            throw BizException("ATTEMPT_HANDLED", "扫码记录 ${attempt.attemptNo} 已处理")
        }
        // 客服介入留痕：按初判生成工单并驳回（不退款），回复引用订单与定位证据
        val ticket = openSupportTicket(
            attempt, attempt.bookingNo?.let { bookingRepo.findByBookingNo(it) },
            when (attempt.classification) {
                DoorFailureClassification.WRONG_STORE, DoorFailureClassification.EARLY_ARRIVAL -> DisputeCause.OTHER
                DoorFailureClassification.ACCOUNT_NOT_ELIGIBLE -> DisputeCause.PRIVILEGE_ABUSE
                DoorFailureClassification.DOOR_FAULT_SUSPECTED -> DisputeCause.DEVICE_FAULT
            },
            title = "[扫码驳回] ${attempt.classification}",
            description = attempt.suggestedReply
        )
        ticket?.let {
            it.responsibility = Responsibility.USER
            it.status = TicketStatus.RESOLVED
            it.resolvedAt = clock.now()
            it.staffNote = note
            attempt.supportTicketNo = it.ticketNo
        }
        attempt.status = DoorAttemptStatus.REJECTED
        attempt.staffNote = note
        attempt.resolvedAt = clock.now()
        attempt.updatedAt = clock.now()
        timeline.record(
            "DOOR_ATTEMPT_REJECTED", attempt.store.id, attempt.bookingNo, "STAFF",
            "${attempt.attemptNo} 驳回（${attempt.classification}）：${attempt.suggestedReply.take(160)}；客服备注 $note"
        )
        return attempt
    }

    // ---------------- 批量补偿后：客服逐个确认是否实际进入 ----------------

    @Transactional
    fun confirmBatchEntry(eventNo: String, attemptNo: String, entered: Boolean, note: String? = null): StoreDoorEvent {
        val event = doorEventRepo.findByEventNo(eventNo) ?: throw NotFoundException("门店事件批次不存在")
        val attempt = attemptRepo.findByAttemptNo(attemptNo) ?: throw NotFoundException("扫码记录不存在")
        if (attempt.batchNo != eventNo) throw BizException("BATCH_MISMATCH", "该扫码记录不属于批次 $eventNo")
        if (attempt.status != DoorAttemptStatus.BATCH_COMPENSATED) {
            throw BizException("ATTEMPT_HANDLED", "扫码记录状态为 ${attempt.status}，无需再确认")
        }
        val now = clock.now()
        val booking = attempt.bookingNo?.let { bookingRepo.findByBookingNo(it) }

        if (entered) {
            attempt.status = DoorAttemptStatus.CONFIRMED_ENTERED
            attempt.actualEntered = true
            // EXTEND 批次：确认实际进入时补登人工通道放行（批量阶段只延长窗口未放行）
            if (event.compensationType == CompensationType.EXTEND_TIME &&
                booking != null && booking.status == BookingStatus.CONFIRMED
            ) {
                val minutes = event.compensationMinutes
                val decision = accessService.enterManually(
                    booking, attempt.user, attempt.store, attempt.doorId, now,
                    "整店门禁事件 ${event.eventNo}：客服核实用户已到店，补登人工通道放行（补偿延长 $minutes 分钟）"
                )
                timeline.record(
                    "DOOR_FAULT_MANUAL_ENTRY", event.store.id, booking.bookingNo, "STAFF",
                    "${attempt.attemptNo} 批次核实后人工通道放行：${decision.result} 座位 ${decision.seatCode}"
                )
            }
            // REFUND 批次中被发现实际进入：批量时按未进入全额退，需改判并留证（差额转人工追缴，不自动扣款）
            if (event.compensationType == CompensationType.REFUND_FEE) {
                val overRefund = booking?.let { reviseRefundForActualEntry(it, attempt, event, now) }
                attempt.staffNote = listOfNotNull(note, overRefund?.let { "实际已进入，原全额退款应冲正，差额 $it 元转人工追缴" })
                    .joinToString("；")
            } else if (note != null) {
                attempt.staffNote = note
            }
            event.enteredCount += 1
            timeline.record("DOOR_BATCH_ENTRY_CONFIRMED", event.store.id, attempt.bookingNo, "STAFF",
                "${attempt.attemptNo} 经监控/人工核实实际进入")
        } else {
            attempt.status = DoorAttemptStatus.CONFIRMED_NOT_ENTERED
            attempt.actualEntered = false
            // EXTEND 批次中确认未进入：延长无意义，改按受影响时段全额退并关闭订单
            if (event.compensationType == CompensationType.EXTEND_TIME && booking != null &&
                booking.status == BookingStatus.CONFIRMED && booking.actualCheckIn == null
            ) {
                val refund = applyRefundForAffectedPeriod(booking, event.incidentNo, attempt.attemptNo, now)
                attempt.compensatedAmount = refund
                attempt.compensationType = CompensationType.REFUND_FEE
            }
            attempt.staffNote = note
            event.notEnteredCount += 1
            timeline.record("DOOR_BATCH_NOT_ENTERED", event.store.id, attempt.bookingNo, "STAFF",
                "${attempt.attemptNo} 核实未实际进入，按未进入用户处理")
        }
        attempt.resolvedAt = now
        attempt.updatedAt = now

        val pending = attemptRepo.findByBatchNoOrderByScannedAtAsc(eventNo)
            .count { it.status == DoorAttemptStatus.BATCH_COMPENSATED }
        if (pending == 0) {
            event.status = CompensationBatchStatus.ALL_CHECKED
            event.allCheckedAt = now
            timeline.record("DOOR_EVENT_BATCH_ALL_CHECKED", event.store.id, null, "STAFF",
                "$eventNo 共 ${event.totalAttempts} 人全部核实：实际进入 ${event.enteredCount}，未进入 ${event.notEnteredCount}")
        }
        return event
    }

    /**
     * 实际进入者的退款改判：按等待时长（计划开始到到店扫码/进场）折算应退金额，
     * 返回批量全退与应退之间的差额（留证转人工追缴，系统不自动扣款）。
     */
    private fun reviseRefundForActualEntry(
        booking: Booking, attempt: DoorScanAttempt, event: StoreDoorEvent, now: Instant
    ): BigDecimal? {
        // 批量退费订单系统内没有进场记录，以扫码时间作为到店时间代理
        val arrival = booking.actualCheckIn ?: attempt.scannedAt
        val totalMin = Duration.between(booking.startTime, booking.endTime).toMinutes().coerceAtLeast(1)
        val waitMin = Duration.between(booking.startTime, arrival).toMinutes().coerceIn(0, totalMin)
        val shouldRefund = booking.price.multiply(BigDecimal.valueOf(waitMin.toDouble() / totalMin))
            .setScale(2, java.math.RoundingMode.HALF_UP)
        val paid = attempt.compensatedAmount ?: booking.price.add(booking.deposit)
        val diff = paid.subtract(shouldRefund).max(BigDecimal.ZERO).setScale(2, java.math.RoundingMode.HALF_UP)
        timeline.record(
            "DOOR_BATCH_REFUND_REVISED", booking.store.id, booking.bookingNo, "STAFF",
            "${attempt.attemptNo} 实际进入（等待 $waitMin/$totalMin 分钟），应退 $shouldRefund 元，" +
                "批量已退 $paid 元，差额 $diff 元转人工追缴；防止借设备故障名义全额退款"
        )
        return diff
    }

    // ---------------- 维修工单 ----------------

    /**
     * 客服介入留痕：有关联预约时生成客服工单（与投诉工单同表，证据包可按单号回放）；
     * 无预约的扫码记录（NONE）不进入以预约为聚合根的证据包，仅保留扫码记录与时间轴。
     */
    private fun openSupportTicket(
        attempt: DoorScanAttempt, booking: Booking?, cause: DisputeCause, title: String, description: String
    ): SupportTicket? {
        if (booking == null) {
            timeline.record(
                "DOOR_STAFF_REVIEW_ONLY", attempt.store.id, null, "STAFF",
                "扫码记录 ${attempt.attemptNo} 无关联预约，客服处理结论随扫码记录留存（$cause）"
            )
            return null
        }
        val t = ticketRepo.save(
            SupportTicket(
                ticketNo = "TMP", store = attempt.store, user = attempt.user,
                bookingNo = booking.bookingNo,
                cause = cause, title = title.take(100),
                description = description.take(1000),
                incidentNo = attempt.incidentNo,
                monitorEventNos = listOfNotNull(attempt.monitorEventNo).joinToString().ifBlank { null },
                createdAt = clock.now(), updatedAt = clock.now()
            )
        )
        t.ticketNo = BizNoGenerator.ticket(t.id!!)
        timeline.record(
            "SUPPORT_TICKET_OPENED", attempt.store.id, booking?.bookingNo, "STAFF",
            "${t.ticketNo} 扫码记录 ${attempt.attemptNo} 客服介入，归因 $cause"
        )
        return t
    }

    private fun openRepairTicket(store: Store, incidentNo: String, doorId: String?, errorCode: DoorErrorCode): RepairTicket {
        val r = repairRepo.save(
            RepairTicket(
                repairNo = "TMP", store = store, incidentNo = incidentNo, doorId = doorId,
                type = IncidentType.DOOR_FAILURE,
                description = "门店闸机 ${doorId ?: "MAIN"} 无法识别/打不开（最近错误码 $errorCode），需现场检修；" +
                    "设备事件 $incidentNo 未解决前门禁对新扫码用户一律拒绝",
                openedBy = "SYSTEM", openedAt = clock.now()
            )
        )
        r.repairNo = BizNoGenerator.repair(r.id!!)
        timeline.record("REPAIR_TICKET_OPENED", store.id, null, "SYSTEM",
            "${r.repairNo} 门店维修工单已生成，关联设备事件 $incidentNo")
        return r
    }

    @Transactional
    fun resolveRepairTicket(repairNo: String, resolution: String, assignee: String?, resolveIncident: Boolean): RepairTicket {
        val r = repairRepo.findByRepairNo(repairNo) ?: throw NotFoundException("维修工单不存在")
        if (r.status == RepairTicketStatus.RESOLVED) throw BizException("REPAIR_CLOSED", "维修工单已完成")
        r.status = RepairTicketStatus.RESOLVED
        r.resolvedAt = clock.now()
        r.resolution = resolution
        assignee?.let { r.assignee = it }
        timeline.record("REPAIR_TICKET_RESOLVED", r.store.id, null, "STAFF",
            "$repairNo：$resolution")
        if (resolveIncident) {
            incidentRepo.findByIncidentNo(r.incidentNo)?.let { inc ->
                if (inc.status == IncidentStatus.OPEN) {
                    incidentService.resolve(r.incidentNo, "维修工单 $repairNo 完成：$resolution")
                }
            }
        }
        return r
    }

    // ---------------- 查询 ----------------

    @Transactional(readOnly = true)
    fun listAttempts(storeId: Long, status: DoorAttemptStatus?): List<DoorScanAttempt> =
        attemptRepo.findByStoreIdOrderByScannedAtDesc(storeId).let { all ->
            if (status == null) all else all.filter { it.status == status }
        }

    @Transactional(readOnly = true)
    fun getAttempt(attemptNo: String): DoorScanAttempt =
        attemptRepo.findByAttemptNo(attemptNo) ?: throw NotFoundException("扫码记录不存在")

    @Transactional(readOnly = true)
    fun listBatches(storeId: Long): List<StoreDoorEvent> =
        doorEventRepo.findByStoreIdOrderByOpenedAtDesc(storeId)

    @Transactional(readOnly = true)
    fun getBatch(eventNo: String): StoreDoorEvent =
        doorEventRepo.findByEventNo(eventNo) ?: throw NotFoundException("门店事件批次不存在")

    @Transactional(readOnly = true)
    fun listBatchAttempts(eventNo: String): List<DoorScanAttempt> =
        attemptRepo.findByBatchNoOrderByScannedAtAsc(eventNo)

    @Transactional(readOnly = true)
    fun listRepairTickets(storeId: Long): List<RepairTicket> =
        repairRepo.findByStoreIdOrderByOpenedAtDesc(storeId)

    @Transactional(readOnly = true)
    fun getRepairTicket(repairNo: String): RepairTicket =
        repairRepo.findByRepairNo(repairNo) ?: throw NotFoundException("维修工单不存在")

    // ---------------- 定位距离 ----------------

    private fun distanceMeters(lat1: Double?, lng1: Double?, lat2: Double?, lng2: Double?): Double? {
        if (lat1 == null || lng1 == null || lat2 == null || lng2 == null) return null
        val r = 6_371_000.0
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dPhi = Math.toRadians(lat2 - lat1)
        val dLambda = Math.toRadians(lng2 - lng1)
        val h = sin(dPhi / 2).let { it * it } + cos(p1) * cos(p2) * sin(dLambda / 2).let { it * it }
        return 2 * r * atan2(sqrt(h), sqrt(1 - h))
    }

    companion object {
        /** 回查同一时段的前后窗口（分钟） */
        const val WINDOW_MINUTES = 30L
        /** 去重扫码失败用户数达到该值，判定为整店门禁异常 */
        const val BATCH_USER_THRESHOLD = 3
        /** 扫码定位距门店的容差（米），超出视为无法证明到店 */
        const val LOCATION_TOLERANCE_METERS = 300
    }
}
