package com.studyroom.domain

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant

@Entity
@Table(
    name = "booking",
    indexes = [
        Index(name = "idx_booking_store_time", columnList = "store_id,startTime,endTime"),
        Index(name = "idx_booking_user", columnList = "user_id"),
        Index(name = "idx_booking_status", columnList = "status")
    ]
)
class Booking(
    @Column(nullable = false, unique = true) var bookingNo: String,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    var user: AppUser,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id")
    var store: Store,

    /** 包月浮动权益下单时可空，进场时必须分配座位 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seat_id")
    var seat: Seat? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id")
    var plan: MembershipPlan? = null,

    @Enumerated(EnumType.STRING) @Column(nullable = false) var type: BookingType,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var status: BookingStatus = BookingStatus.PENDING_PAYMENT,

    /** 计划开始（包月=权益开始日 00:00） */
    @Column(nullable = false) var startTime: Instant,
    /** 计划结束（包月=权益结束日 24:00） */
    @Column(nullable = false) var endTime: Instant,

    /** 已确认的延时后结束时间，null 表示未延时 */
    var extendedEndTime: Instant? = null,
    @Column(nullable = false) var extended: Boolean = false,
    /** 门禁故障补偿：免费延长的分钟数（不收费，进场迟到宽限同步延长） */
    @Column(nullable = false) var compGraceMinutes: Long = 0,
    /** 门禁故障补偿：免费延长后的结束时间（门店责任，不计延时费/延时率） */
    var compEndTime: Instant? = null,

    var actualCheckIn: Instant? = null,
    var actualCheckOut: Instant? = null,

    @Column(nullable = false) var price: BigDecimal = BigDecimal.ZERO,
    @Column(nullable = false) var deposit: BigDecimal = BigDecimal.ZERO,
    /** 已支付总额（费用+押金） */
    @Column(nullable = false) var paidAmount: BigDecimal = BigDecimal.ZERO,
    /** 已完成退款总额（含押金退还） */
    @Column(nullable = false) var refundedAmount: BigDecimal = BigDecimal.ZERO,
    /** 延时/超时追加费用 */
    @Column(nullable = false) var extraChargedAmount: BigDecimal = BigDecimal.ZERO,
    /** 押金中被扣除的赔偿金额 */
    @Column(nullable = false) var depositDeducted: BigDecimal = BigDecimal.ZERO,

    @Column(nullable = false) var useLocker: Boolean = false,
    @Column(nullable = false) var silenceRuleAcknowledged: Boolean = false,
    @Column(nullable = false) var late: Boolean = false,
    @Column(nullable = false) var lateMinutes: Long = 0,

    /** 离场核对：储物柜已清空归还 */
    @Column(nullable = false) var lockerReturned: Boolean = true,
    /** 离场核对：座位设备完好 */
    @Column(nullable = false) var seatEquipmentOk: Boolean = true,
    /** 关联的设备/停电事件编号（因门店责任受影响时） */
    var relatedIncidentNo: String? = null,
    /** 若本预约是迟到顺延碰撞中被换座的一方，记录原座位 */
    var originalSeatId: Long? = null,

    @Column(nullable = false) var createdAt: Instant = Instant.now(),
    @Version @Column(nullable = false) var version: Long = 0,
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null
) {
    /** 结算/占座截止时间：付费延时与门禁故障免费延长取更晚者 */
    val effectiveEndTime: Instant
        get() = listOfNotNull(extendedEndTime, compEndTime, endTime).max()
}

@Entity
@Table(name = "charge")
class Charge(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id")
    var booking: Booking,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var type: ChargeType,
    @Column(nullable = false, precision = 10, scale = 2) var amount: BigDecimal,
    @Column(nullable = false) var createdAt: Instant = Instant.now(),
    var note: String? = null,
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null
)

@Entity
@Table(name = "refund")
class Refund(
    /** 保存后回填正式编号（同事务内可能连续产生多笔退款，不能预置占位值） */
    var refundNo: String? = null,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id")
    var booking: Booking,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var reason: RefundReason,
    @Column(nullable = false, precision = 10, scale = 2) var amount: BigDecimal,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var status: RefundStatus = RefundStatus.PENDING,
    /** 是否为押金退还（押金默认原路退回，不属于赔偿） */
    @Column(nullable = false) var depositRefund: Boolean = false,
    var ticketNo: String? = null,
    var incidentNo: String? = null,
    @Column(nullable = false) var createdAt: Instant = Instant.now(),
    var completedAt: Instant? = null,
    var note: String? = null,
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null
)
