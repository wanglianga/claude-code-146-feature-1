package com.studyroom.web

import com.studyroom.domain.*
import com.studyroom.service.CheckoutSettlement
import com.studyroom.service.StoreMetrics
import com.studyroom.service.TicketEvidence
import java.math.BigDecimal
import java.time.Instant

// ---------------- 请求 ----------------

data class CreateUserRequest(
    val phone: String,
    val name: String,
    val realNameVerified: Boolean = false,
    val role: UserRole = UserRole.USER
)

data class CreateBookingRequest(
    val userId: Long,
    val storeId: Long,
    val seatId: Long? = null,
    val planId: Long? = null,
    val type: BookingType,
    val startTime: Instant? = null,
    val endTime: Instant? = null,
    val useLocker: Boolean = false,
    val silenceRuleAcknowledged: Boolean = false
)

data class ExtendRequest(val minutes: Long)

data class EnterRequest(val userId: Long, val storeId: Long, val doorId: String? = null)

data class LeaveRequest(
    val userId: Long,
    val storeId: Long,
    val lockerReturned: Boolean = true,
    val seatEquipmentOk: Boolean = true,
    /** NORMAL / EARLY_LEAVE / STORE_FAULT */
    val mode: String = "NORMAL",
    val incidentNo: String? = null,
    val doorId: String? = null
)

data class IncidentRequest(
    val storeId: Long,
    val type: IncidentType,
    val description: String,
    val seatId: Long? = null
)

data class ResolveIncidentRequest(val resolution: String)

data class BlackoutRequest(val blackout: Boolean, val reason: String = "门店临时停电")

data class ComplaintRequest(
    val bookingNo: String,
    val category: ComplaintCategory,
    val description: String,
    val againstBookingNo: String? = null,
    val incidentNo: String? = null
)

data class ResolveComplaintRequest(val resolution: String, val rejected: Boolean = false)

data class AppealRequest(val text: String)

data class DecideTicketRequest(
    val responsibility: Responsibility,
    val refundAmount: BigDecimal? = null,
    val note: String
)

data class SensorReportRequest(
    val storeId: Long,
    val seatId: Long,
    val occupied: Boolean,
    val occupiedByBookingNo: String? = null
)

data class PriceAdjustRequest(val priceMultiplier: Double, val note: String? = null)
data class SeatAdjustRequest(val zone: String? = null, val hourlyPrice: Double? = null, val active: Boolean? = null)
data class PlanAdjustRequest(val price: Double? = null, val active: Boolean? = null, val fixedSeat: Boolean? = null, val days: Int? = null)

data class ClockOverrideRequest(val time: Instant?)

// ---------------- 门禁扫码失败补偿 ----------------

data class DoorScanFailureRequest(
    val userId: Long,
    val storeId: Long,
    val errorCode: DoorErrorCode,
    val doorId: String? = null,
    val clientLat: Double? = null,
    val clientLon: Double? = null
)

/** EXTEND=免费延长使用时间；REFUND=退还受影响时段费用（延长遇碰撞自动回退退费） */
data class ConfirmDoorFaultRequest(
    val preference: com.studyroom.service.CompensationPreference = com.studyroom.service.CompensationPreference.EXTEND,
    /** 可选：客服核实的受影响分钟数；不传则取扫码时刻到确认时刻的等待时长 */
    val affectedMinutes: Long? = null,
    val staffId: Long? = null,
    val note: String? = null
)

data class ConfirmBatchEntryRequest(
    val actuallyEntered: Boolean,
    /** 实际进入时间（不传取当前时间，用于核算等待时长） */
    val enteredAt: Instant? = null,
    val staffId: Long? = null,
    val note: String? = null
)

data class DoorScanReplyRequest(val note: String, val staffId: Long? = null)

// ---------------- 视图 ----------------

data class UserView(
    val id: Long, val phone: String, val name: String,
    val realNameVerified: Boolean, val role: UserRole,
    val noShowCount: Int, val blacklisted: Boolean
)

data class StoreView(
    val id: Long, val name: String, val address: String,
    val unattended: Boolean, val blackout: Boolean,
    val lateGraceMinutes: Int, val earlyEntryMinutes: Int,
    val priceMultiplier: Double, val earlyLeaveRefundRate: Double,
    val overtimeRate: Double,
    val latitude: Double?, val longitude: Double?
)

data class SeatView(
    val id: Long, val storeId: Long, val code: String,
    val type: SeatType, val status: SeatStatus, val zone: String,
    val hourlyPrice: BigDecimal, val lockerAvailable: Boolean, val active: Boolean,
    val sensorOccupied: Boolean?,
    val sensorOccupiedBy: String?
)

data class PlanView(
    val id: Long, val storeId: Long, val name: String,
    val seatType: SeatType?, val price: BigDecimal, val deposit: BigDecimal,
    val days: Int, val fixedSeat: Boolean, val active: Boolean
)

data class BookingView(
    val bookingNo: String,
    val userId: Long, val storeId: Long,
    val seatId: Long?, val seatCode: String?,
    val planId: Long?,
    val type: BookingType, val status: BookingStatus,
    val startTime: Instant, val endTime: Instant,
    val extendedEndTime: Instant?, val extended: Boolean,
    val compEndTime: Instant?, val compGraceMinutes: Long, val effectiveEndTime: Instant,
    val actualCheckIn: Instant?, val actualCheckOut: Instant?,
    val price: BigDecimal, val deposit: BigDecimal,
    val paidAmount: BigDecimal, val refundedAmount: BigDecimal,
    val extraChargedAmount: BigDecimal, val depositDeducted: BigDecimal,
    val useLocker: Boolean, val silenceRuleAcknowledged: Boolean,
    val late: Boolean, val lateMinutes: Long,
    val lockerReturned: Boolean, val seatEquipmentOk: Boolean,
    val relatedIncidentNo: String?, val originalSeatId: Long?
)

data class AccessLogView(
    val id: Long, val bookingNo: String?, val userId: Long, val storeId: Long,
    val doorId: String?, val direction: AccessDirection, val result: AccessResult,
    val detail: String?, val monitorEventNo: String?, val createdAt: Instant
)

data class ChargeView(val id: Long, val type: ChargeType, val amount: BigDecimal, val note: String?, val createdAt: Instant)
data class RefundView(
    val refundNo: String, val type: RefundReason, val amount: BigDecimal,
    val status: RefundStatus, val depositRefund: Boolean,
    val ticketNo: String?, val incidentNo: String?, val note: String?,
    val createdAt: Instant, val completedAt: Instant?
)

data class IncidentView(
    val incidentNo: String, val storeId: Long, val seatCode: String?,
    val type: IncidentType, val status: IncidentStatus,
    val description: String, val monitorEventNo: String?,
    val openedAt: Instant, val resolvedAt: Instant?, val resolution: String?
)

data class ComplaintView(
    val complaintNo: String, val storeId: Long, val complainantUserId: Long,
    val bookingNo: String, val againstBookingNo: String?,
    val category: ComplaintCategory, val status: ComplaintStatus,
    val description: String, val relatedTicketNo: String?,
    val relatedIncidentNo: String?, val monitorEventNo: String?,
    val createdAt: Instant, val resolvedAt: Instant?, val resolution: String?
)

data class TicketView(
    val ticketNo: String, val storeId: Long, val userId: Long,
    val bookingNo: String, val cause: DisputeCause, val status: TicketStatus,
    val responsibility: Responsibility, val title: String, val description: String,
    val userAppeal: String?, val staffNote: String?,
    val monitorEventNos: String?, val incidentNo: String?, val complaintNo: String?,
    val createdAt: Instant, val resolvedAt: Instant?
)

data class TimelineEventView(
    val id: Long, val eventType: String, val occurredAt: Instant,
    val storeId: Long?, val bookingNo: String?, val actor: String?, val detail: String?
)

data class SettlementView(
    val overtimeFee: BigDecimal, val earlyRefund: BigDecimal,
    val depositDeduction: BigDecimal, val depositReturned: BigDecimal, val note: String
)

data class EntryDecisionView(
    val granted: Boolean, val result: AccessResult,
    val bookingNo: String?, val seatCode: String?, val detail: String
)

data class ExitResultView(
    val bookingNo: String, val seatCode: String,
    val settlement: SettlementView, val status: BookingStatus, val detail: String
)

data class SensorView(
    val seatId: Long, val occupied: Boolean, val occupiedByBookingNo: String?, val updatedAt: Instant
)

data class MetricsView(
    val storeId: Long, val from: Instant, val to: Instant,
    val activeSeats: Int, val checkedInBookings: Int, val extendedBookings: Int,
    val complaints: Long, val deviceIncidents: Long,
    val occupancyRate: Double, val extensionRate: Double, val complaintRate: Double,
    val disputesByCause: Map<String, Long>
)

data class EvidenceView(
    val ticket: TicketView,
    val booking: BookingView,
    val accessLogs: List<AccessLogView>,
    val timeline: List<TimelineEventView>,
    val sensor: SensorView?,
    val refunds: List<RefundView>,
    val complaint: ComplaintView?,
    val incident: IncidentView?,
    val doorScans: List<DoorScanView>
)

data class DoorScanView(
    val scanNo: String, val userId: Long, val storeId: Long,
    val doorId: String?, val errorCode: DoorErrorCode,
    val classification: DoorScanClassification, val status: DoorScanStatus,
    val scannedAt: Instant,
    val bookingNo: String?, val paymentSnapshot: String,
    val bookingStartTime: Instant?, val bookingEndTime: Instant?,
    val clientLat: Double?, val clientLon: Double?,
    val storeLat: Double?, val storeLon: Double?, val locationDistanceMeters: Double?,
    val monitorEventNo: String?, val detail: String?,
    val ticketNo: String?, val incidentNo: String?, val batchNo: String?,
    val compensationType: CompensationType, val compensationMinutes: Long,
    val refundNo: String?, val refundAmount: BigDecimal,
    val actuallyEntered: Boolean?, val confirmedBy: Long?, val confirmedAt: Instant?,
    val replyNote: String?, val updatedAt: Instant
)

data class DoorBatchView(
    val batchNo: String, val storeId: Long, val incidentNo: String,
    val windowFrom: Instant, val windowTo: Instant, val status: DoorScanStatus,
    val ruleDescription: String,
    val affectedCount: Int, val confirmedCount: Int, val refundedCount: Int,
    val createdAt: Instant, val resolvedAt: Instant?
)

object Views {
    fun of(u: AppUser) = UserView(u.id!!, u.phone, u.name, u.realNameVerified, u.role, u.noShowCount, u.blacklisted)
    fun of(s: Store) = StoreView(
        s.id!!, s.name, s.address, s.unattended, s.blackout,
        s.lateGraceMinutes, s.earlyEntryMinutes, s.priceMultiplier,
        s.earlyLeaveRefundRate, s.overtimeRate, s.latitude, s.longitude
    )
    fun of(seat: Seat, sensor: SeatSensorReading? = null) = SeatView(
        seat.id!!, seat.store.id!!, seat.code, seat.seatType, seat.status, seat.zone,
        seat.hourlyPrice, seat.lockerAvailable, seat.active,
        sensor?.occupied, sensor?.occupiedByBookingNo
    )
    fun of(p: MembershipPlan) = PlanView(
        p.id!!, p.store.id!!, p.name, p.seatType, p.price, p.deposit, p.days, p.fixedSeat, p.active
    )
    fun of(b: Booking) = BookingView(
        b.bookingNo, b.user.id!!, b.store.id!!, b.seat?.id, b.seat?.code, b.plan?.id,
        b.type, b.status, b.startTime, b.endTime, b.extendedEndTime, b.extended,
        b.compEndTime, b.compGraceMinutes, b.effectiveEndTime,
        b.actualCheckIn, b.actualCheckOut,
        b.price, b.deposit, b.paidAmount, b.refundedAmount, b.extraChargedAmount, b.depositDeducted,
        b.useLocker, b.silenceRuleAcknowledged, b.late, b.lateMinutes,
        b.lockerReturned, b.seatEquipmentOk, b.relatedIncidentNo, b.originalSeatId
    )
    fun of(l: AccessLog) = AccessLogView(
        l.id!!, l.bookingNo, l.user.id!!, l.store.id!!, l.doorId,
        l.direction, l.result, l.detail, l.monitorEventNo, l.createdAt
    )
    fun of(c: Charge) = ChargeView(c.id!!, c.type, c.amount, c.note, c.createdAt)
    fun of(r: Refund) = RefundView(
        r.refundNo!!, r.reason, r.amount, r.status, r.depositRefund,
        r.ticketNo, r.incidentNo, r.note, r.createdAt, r.completedAt
    )
    fun of(i: Incident) = IncidentView(
        i.incidentNo, i.store.id!!, i.seat?.code, i.type, i.status,
        i.description, i.monitorEventNo, i.openedAt, i.resolvedAt, i.resolution
    )
    fun of(c: Complaint) = ComplaintView(
        c.complaintNo, c.store.id!!, c.complainant.id!!, c.bookingNo, c.againstBookingNo,
        c.category, c.status, c.description, c.relatedTicketNo, c.relatedIncidentNo,
        c.monitorEventNo, c.createdAt, c.resolvedAt, c.resolution
    )
    fun of(t: SupportTicket) = TicketView(
        t.ticketNo, t.store.id!!, t.user.id!!, t.bookingNo, t.cause, t.status,
        t.responsibility, t.title, t.description, t.userAppeal, t.staffNote,
        t.monitorEventNos, t.incidentNo, t.complaintNo, t.createdAt, t.resolvedAt
    )
    fun of(e: TimelineEvent) = TimelineEventView(e.id!!, e.eventType, e.occurredAt, e.storeId, e.bookingNo, e.actor, e.detail)
    fun of(s: CheckoutSettlement) = SettlementView(s.overtimeFee, s.earlyRefund, s.depositDeduction, s.depositReturned, s.note)
    fun of(m: StoreMetrics) = MetricsView(
        m.storeId, m.from, m.to, m.activeSeats, m.checkedInBookings, m.extendedBookings,
        m.complaints, m.deviceIncidents, m.occupancyRate, m.extensionRate, m.complaintRate,
        m.disputesByCause
    )
    fun of(r: SeatSensorReading) = SensorView(r.seat.id!!, r.occupied, r.occupiedByBookingNo, r.updatedAt)
    fun of(e: TicketEvidence) = EvidenceView(
        of(e.ticket), of(e.booking),
        e.accessLogs.map { of(it) }, e.timeline.map { of(it) },
        e.sensor?.let { of(it) }, e.refunds.map { of(it) },
        e.complaint?.let { of(it) }, e.incident?.let { of(it) },
        e.doorScans.map { of(it) }
    )

    fun of(s: DoorScanAttempt) = DoorScanView(
        s.scanNo, s.user.id!!, s.store.id!!, s.doorId, s.errorCode,
        s.classification, s.status, s.scannedAt,
        s.bookingNo, s.paymentSnapshot, s.bookingStartTime, s.bookingEndTime,
        s.clientLat, s.clientLon, s.storeLat, s.storeLon, s.locationDistanceMeters,
        s.monitorEventNo, s.detail,
        s.ticketNo, s.incidentNo, s.batchNo,
        s.compensationType, s.compensationMinutes, s.refundNo, s.refundAmount,
        s.actuallyEntered, s.confirmedBy, s.confirmedAt, s.replyNote, s.updatedAt
    )

    fun of(b: DoorCompensationBatch) = DoorBatchView(
        b.batchNo, b.store.id!!, b.incidentNo, b.windowFrom, b.windowTo, b.status,
        b.ruleDescription, b.affectedCount, b.confirmedCount, b.refundedCount,
        b.createdAt, b.resolvedAt
    )
}
