package com.studyroom.service

import com.studyroom.domain.*
import com.studyroom.repo.*
import com.studyroom.support.BizException
import com.studyroom.support.NotFoundException
import com.studyroom.support.TimeProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

/** 客服工单：聚合门禁日志、座位传感器、用户申诉、店员处理、监控事件编号等证据 */
data class TicketEvidence(
    val ticket: SupportTicket,
    val accessLogs: List<AccessLog>,
    val timeline: List<TimelineEvent>,
    val sensor: SeatSensorReading?,
    val booking: Booking,
    val refunds: List<Refund>,
    val complaint: Complaint?,
    val incident: Incident?,
    val doorScans: List<DoorScanAttempt>
)

data class TicketDecision(
    val responsibility: Responsibility,
    val refundAmount: BigDecimal?,
    val note: String
)

@Service
class SupportService(
    private val ticketRepo: SupportTicketRepository,
    private val bookingRepo: BookingRepository,
    private val accessLogRepo: AccessLogRepository,
    private val timelineRepo: TimelineEventRepository,
    private val sensorRepo: SeatSensorRepository,
    private val refundRepo: RefundRepository,
    private val complaintRepo: ComplaintRepository,
    private val incidentRepo: IncidentRepository,
    private val doorScanRepo: DoorScanAttemptRepository,
    private val billing: BillingService,
    private val tl: TimelineService,
    private val clock: TimeProvider
) {
    @Transactional(readOnly = true)
    fun evidence(ticketNo: String): TicketEvidence {
        val ticket = ticketRepo.findByTicketNo(ticketNo) ?: throw NotFoundException("工单不存在")
        val booking = bookingRepo.findByBookingNo(ticket.bookingNo) ?: throw NotFoundException("关联预约不存在")
        val complaint = ticket.complaintNo?.let { complaintRepo.findByComplaintNo(it) }
        val incident = ticket.incidentNo?.let { incidentRepo.findByIncidentNo(it) }
            ?: complaint?.relatedIncidentNo?.let { incidentRepo.findByIncidentNo(it) }
        return TicketEvidence(
            ticket = ticket,
            accessLogs = accessLogRepo.findByBookingNoOrderByCreatedAtAsc(ticket.bookingNo),
            timeline = timelineRepo.findByBookingNoOrderByOccurredAtAsc(ticket.bookingNo),
            sensor = booking.seat?.let { sensorRepo.findBySeatId(it.id!!) },
            booking = booking,
            refunds = refundRepo.findByBookingIdOrderByCreatedAtDesc(booking.id!!),
            complaint = complaint,
            incident = incident,
            doorScans = doorScanRepo.findByTicketNo(ticketNo)
        )
    }

    /** 用户补充申诉 */
    @Transactional
    fun appeal(ticketNo: String, text: String): SupportTicket {
        val t = ticketRepo.findByTicketNo(ticketNo) ?: throw NotFoundException("工单不存在")
        t.userAppeal = text
        t.updatedAt = clock.now()
        tl.record("TICKET_APPEAL", t.store.id, t.bookingNo, "USER:${t.user.id}", text.take(200))
        return t
    }

    /**
     * 客服判定责任并结案。
     * - STORE：按门店责任退款（费用/押金），如关联设备事件需推动其处理；
     * - USER：可不退，必要时记录滥用（调用方可配合黑名单/爽约策略）；
     * - SHARED：按申请金额退款；
     * 结案时同步关闭关联投诉。退款完成与工单结案在同一事务内。
     */
    @Transactional
    fun decide(ticketNo: String, decision: TicketDecision): SupportTicket {
        val t = ticketRepo.findByTicketNo(ticketNo) ?: throw NotFoundException("工单不存在")
        if (t.status == TicketStatus.CLOSED || t.status == TicketStatus.RESOLVED) {
            throw BizException("TICKET_CLOSED", "工单已结案")
        }
        val booking = bookingRepo.findByBookingNo(t.bookingNo) ?: throw NotFoundException("关联预约不存在")
        t.responsibility = decision.responsibility
        t.staffNote = decision.note

        if (decision.refundAmount != null && decision.refundAmount.signum() > 0 &&
            decision.responsibility != Responsibility.USER
        ) {
            val reason = when {
                t.cause == DisputeCause.DEVICE_FAULT -> RefundReason.STORE_FAULT
                booking.originalSeatId != null -> RefundReason.COLLISION_REASSIGN
                else -> RefundReason.DISPUTE_APPROVED
            }
            val r = billing.refund(
                booking, reason, decision.refundAmount,
                ticketNo = t.ticketNo, incidentNo = t.incidentNo,
                note = "工单 ${t.ticketNo} 责任判定 ${decision.responsibility}"
            )
            t.staffNote = (decision.note + "；已退款 ${r.amount} 元（${r.refundNo}）")
            tl.record("TICKET_REFUND", t.store.id, t.bookingNo, "STAFF",
                "责任 ${decision.responsibility}，退款 ${r.amount}")
        } else {
            tl.record("TICKET_DECIDE", t.store.id, t.bookingNo, "STAFF",
                "责任 ${decision.responsibility}，无退款：${decision.note}")
        }

        t.status = TicketStatus.RESOLVED
        t.resolvedAt = clock.now()
        t.updatedAt = clock.now()

        t.complaintNo?.let { no ->
            complaintRepo.findByComplaintNo(no)?.let {
                if (it.status == ComplaintStatus.OPEN) {
                    it.status = ComplaintStatus.RESOLVED
                    it.resolvedAt = clock.now()
                    it.resolution = "随工单 $ticketNo 结案：${decision.note.take(100)}"
                }
            }
        }
        return t
    }

    @Transactional(readOnly = true)
    fun listByStore(storeId: Long, cause: DisputeCause?): List<SupportTicket> =
        ticketRepo.findByStoreIdOrderByCreatedAtDesc(storeId).let { all ->
            if (cause == null) all else all.filter { it.cause == cause }
        }
}
