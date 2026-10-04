package com.studyroom.web

import com.studyroom.domain.*
import com.studyroom.service.CheckoutSettlement
import com.studyroom.service.ConfirmFaultResult
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

// ---------------- 门禁打不开自助补偿 ----------------

data class DoorScanRequest(
    val userId: Long,
    val storeId: Long,
    val errorCode: DoorErrorCode,
    val doorId: String? = null,
    /** 扫码设备定位（用于核对是否真的到店） */
    val clientLat: Double? = null,
    val clientLng: Double? = null
)

data class ConfirmDoorFaultRequest(
    val compensationType: CompensationType,
    val minutes: Long = 60,
    val grantManualEntry: Boolean = true,
    val staffNote: String? = null
)

data class RejectDoorAttemptRequest(val note: String)

data class ConfirmBatchEntryRequest(val entered: Boolean, val note: String? = null)

data class ResolveRepairRequest(
    val resolution: String,
    val assignee: String? = null,
    val resolveIncident: Boolean = true
)

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
    val incident: IncidentView?
)

data class DoorScanAttemptView(
    val attemptNo: String,
    val userId: Long, val userName: String, val storeId: Long,
    val bookingNo: String?, val bookingStoreId: Long?, val bookingSeatCode: String?,
    val doorId: String?, val errorCode: DoorErrorCode,
    val scannedAt: Instant,
    val clientLat: Double?, val clientLng: Double?, val locationDistanceMeters: Double?,
    val paymentStatus: String, val bookingStart: Instant?, val bookingEnd: Instant?,
    val classification: DoorFailureClassification, val status: DoorAttemptStatus,
    val suggestedReply: String, val monitorEventNo: String?,
    val incidentNo: String?, val batchNo: String?, val supportTicketNo: String?,
    val compensationType: CompensationType?, val compensationMinutes: Long?,
    val compensatedAmount: BigDecimal?, val actualEntered: Boolean?,
    val staffNote: String?, val resolvedAt: Instant?
)

data class StoreDoorEventView(
    val eventNo: String, val storeId: Long, val incidentNo: String, val doorId: String?,
    val windowFrom: Instant, val windowTo: Instant,
    val status: CompensationBatchStatus,
    val compensationType: CompensationType, val compensationMinutes: Long,
    val description: String, val repairTicketNo: String?,
    val totalAttempts: Int, val enteredCount: Int, val notEnteredCount: Int,
    val openedAt: Instant, val allCheckedAt: Instant?
)

data class RepairTicketView(
    val repairNo: String, val storeId: Long, val incidentNo: String, val doorId: String?,
    val type: IncidentType, val status: RepairTicketStatus,
    val description: String, val assignee: String?, val openedBy: String,
    val openedAt: Instant, val resolvedAt: Instant?, val resolution: String?
)

data class ConfirmFaultView(
    val attempt: DoorScanAttemptView,
    val incidentNo: String,
    val repairNo: String,
    val manualEntry: EntryDecisionView?,
    val batchEventNo: String?,
    val batchAttemptNos: List<String>
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
        e.complaint?.let { of(it) }, e.incident?.let { of(it) }
    )

    fun of(a: DoorScanAttempt) = DoorScanAttemptView(
        a.attemptNo, a.user.id!!, a.user.name, a.store.id!!,
        a.bookingNo, a.bookingStoreId, a.bookingSeatCode,
        a.doorId, a.errorCode, a.scannedAt,
        a.clientLat, a.clientLng, a.locationDistanceMeters,
        a.paymentStatus, a.bookingStart, a.bookingEnd,
        a.classification, a.status, a.suggestedReply, a.monitorEventNo,
        a.incidentNo, a.batchNo, a.supportTicketNo,
        a.compensationType, a.compensationMinutes, a.compensatedAmount,
        a.actualEntered, a.staffNote, a.resolvedAt
    )

    fun of(e: StoreDoorEvent) = StoreDoorEventView(
        e.eventNo, e.store.id!!, e.incidentNo, e.doorId,
        e.windowFrom, e.windowTo, e.status,
        e.compensationType, e.compensationMinutes,
        e.description, e.repairTicketNo,
        e.totalAttempts, e.enteredCount, e.notEnteredCount,
        e.openedAt, e.allCheckedAt
    )

    fun of(r: RepairTicket) = RepairTicketView(
        r.repairNo, r.store.id!!, r.incidentNo, r.doorId,
        r.type, r.status, r.description, r.assignee, r.openedBy,
        r.openedAt, r.resolvedAt, r.resolution
    )

    fun of(r: ConfirmFaultResult) = ConfirmFaultView(
        of(r.attempt), r.incidentNo, r.repairNo,
        r.manualEntry?.let { EntryDecisionView(it.granted, it.result, it.bookingNo, it.seatCode, it.detail) },
        r.batchEventNo, r.batchAttemptNos
    )
}
