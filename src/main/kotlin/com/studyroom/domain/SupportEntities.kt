package com.studyroom.domain

import jakarta.persistence.*
import java.time.Instant

/** 门禁进/出日志：客服争议处理的核心证据之一 */
@Entity
@Table(
    name = "access_log",
    indexes = [Index(name = "idx_access_store_time", columnList = "store_id,createdAt")]
)
class AccessLog(
    /** 拒绝（无有效预约）时为空 */
    var bookingNo: String? = null,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    var user: AppUser,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id")
    var store: Store,
    var doorId: String? = null,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var direction: AccessDirection,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var result: AccessResult,
    @Column(length = 500) var detail: String? = null,
    /** 监控事件编号，可与监控录像互相印证 */
    var monitorEventNo: String? = null,
    @Column(nullable = false) var createdAt: Instant = Instant.now(),
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null
)

/**
 * 统一时间轴：预约、门禁、座位、投诉、设备、计费、退款、工单的每一次状态变化
 * 都追加一条不可变事件。排障与对账时以时间轴为准。
 */
@Entity
@Table(
    name = "timeline_event",
    indexes = [
        Index(name = "idx_tl_booking", columnList = "bookingNo"),
        Index(name = "idx_tl_store_time", columnList = "storeId,occurredAt")
    ]
)
class TimelineEvent(
    @Column(nullable = false) var eventType: String,
    @Column(nullable = false) var occurredAt: Instant,
    var storeId: Long? = null,
    var bookingNo: String? = null,
    var actor: String? = null,
    @Column(length = 1000) var detail: String? = null,
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null
)

@Entity
@Table(name = "incident")
class Incident(
    @Column(nullable = false, unique = true) var incidentNo: String,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id")
    var store: Store,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "seat_id")
    var seat: Seat? = null,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var type: IncidentType,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var status: IncidentStatus = IncidentStatus.OPEN,
    @Column(nullable = false) var description: String,
    var monitorEventNo: String? = null,
    @Column(nullable = false) var openedAt: Instant = Instant.now(),
    var resolvedAt: Instant? = null,
    var resolution: String? = null,
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null
)

@Entity
@Table(name = "complaint")
class Complaint(
    @Column(nullable = false, unique = true) var complaintNo: String,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id")
    var store: Store,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "complainant_id")
    var complainant: AppUser,
    /** 投诉来源预约 */
    @Column(nullable = false) var bookingNo: String,
    /** 被投诉的预约号（如邻座噪音），可空 */
    var againstBookingNo: String? = null,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var category: ComplaintCategory,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var status: ComplaintStatus = ComplaintStatus.OPEN,
    @Column(nullable = false) var description: String,
    var relatedTicketNo: String? = null,
    var relatedIncidentNo: String? = null,
    var monitorEventNo: String? = null,
    @Column(nullable = false) var createdAt: Instant = Instant.now(),
    var resolvedAt: Instant? = null,
    var resolution: String? = null,
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null
)

@Entity
@Table(name = "support_ticket")
class SupportTicket(
    @Column(nullable = false, unique = true) var ticketNo: String,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id")
    var store: Store,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    var user: AppUser,
    @Column(nullable = false) var bookingNo: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var cause: DisputeCause,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var status: TicketStatus = TicketStatus.OPEN,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var responsibility: Responsibility = Responsibility.NONE,
    @Column(nullable = false) var title: String,
    @Column(nullable = false, length = 1000) var description: String,
    /** 用户申诉内容 */
    @Column(length = 1000) var userAppeal: String? = null,
    /** 店员处理记录 */
    @Column(length = 1000) var staffNote: String? = null,
    var monitorEventNos: String? = null,
    var incidentNo: String? = null,
    var complaintNo: String? = null,
    /** 来源：普通投诉 / 门禁扫码失败（单号）/ 整店门禁批量事件 */
    @Column(nullable = false) var source: String = "COMPLAINT",
    @Column(nullable = false) var createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
    var resolvedAt: Instant? = null,
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null
)

/**
 * 门禁扫码失败记录：用户在预约开始后到店但门禁无法识别时落证。
 * 一条记录同时保存三类证据，供客服判定与事后审计：
 * 1) 扫码事实：扫码时间、闸机、门禁错误码、监控编号；
 * 2) 订单/支付事实：匹配到的预约号、预约门店与时间窗、支付状态快照；
 * 3) 定位事实：客户端上报坐标、门店坐标、两者距离差（识别走错门店）。
 * 单账号异常时记录长期保留，防止用户以“设备故障”名义申请全额退款。
 */
@Entity
@Table(
    name = "door_scan_attempt",
    indexes = [
        Index(name = "idx_door_scan_store_time", columnList = "store_id,scannedAt"),
        Index(name = "idx_door_scan_user", columnList = "user_id"),
        Index(name = "idx_door_scan_batch", columnList = "batchNo"),
        Index(name = "idx_door_scan_status", columnList = "status")
    ]
)
class DoorScanAttempt(
    @Column(nullable = false, unique = true) var scanNo: String,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    var user: AppUser,
    /** 实际扫码的门店（可能与预约门店不同——走错门店的关键证据） */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id")
    var store: Store,
    var doorId: String? = null,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var errorCode: DoorErrorCode,
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    var classification: DoorScanClassification,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var status: DoorScanStatus = DoorScanStatus.RECORDED,

    @Column(nullable = false) var scannedAt: Instant = Instant.now(),

    /** 扫码时匹配到的本门店预约（未到时间等场景仍有值；无预约为空） */
    var bookingNo: String? = null,
    /** 支付/订单状态快照，如 CONFIRMED/PENDING_PAYMENT/NO_BOOKING */
    @Column(nullable = false) var paymentSnapshot: String = "NO_BOOKING",
    /** 预约时间窗快照（可能属于别的门店） */
    var bookingStartTime: Instant? = null,
    var bookingEndTime: Instant? = null,

    /** 客户端扫码时上报定位 */
    var clientLat: Double? = null,
    var clientLon: Double? = null,
    /** 扫码门店坐标快照 */
    var storeLat: Double? = null,
    var storeLon: Double? = null,
    /** 客户端定位与扫码门店的直线距离（米） */
    var locationDistanceMeters: Double? = null,

    /** 监控事件编号，与门禁日志一致 */
    var monitorEventNo: String? = null,
    @Column(length = 1000) var detail: String? = null,

    var ticketNo: String? = null,
    var incidentNo: String? = null,
    /** 整店门禁异常补偿批次号 */
    var batchNo: String? = null,

    @Enumerated(EnumType.STRING) @Column(nullable = false)
    var compensationType: CompensationType = CompensationType.NONE,
    /** 免费延长分钟数 */
    @Column(nullable = false) var compensationMinutes: Long = 0,
    var refundNo: String? = null,
    @Column(nullable = false, precision = 10, scale = 2)
    var refundAmount: java.math.BigDecimal = java.math.BigDecimal.ZERO,

    /** 客服确认用户是否实际进入（整店批量补偿逐人确认） */
    var actuallyEntered: Boolean? = null,
    var confirmedBy: Long? = null,
    var confirmedAt: Instant? = null,
    /** 客服回复（引用订单与定位证据） */
    @Column(length = 1000) var replyNote: String? = null,

    @Column(nullable = false) var createdAt: Instant = Instant.now(),
    @Column(nullable = false) var updatedAt: Instant = Instant.now(),
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null
)

/**
 * 整店门禁异常补偿批次：一次确认的门店门禁故障事件对应一个批次。
 * 补偿规则按批次统一生成（默认“免费延长等待时长，结束时间不变的时段按比例退费”），
 * 客服只需对批次内用户逐一确认是否实际进入。
 */
@Entity
@Table(name = "door_compensation_batch")
class DoorCompensationBatch(
    @Column(nullable = false, unique = true) var batchNo: String,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id")
    var store: Store,
    /** 批次关联的门店维修工单（门禁事件） */
    @Column(nullable = false) var incidentNo: String,
    @Column(nullable = false) var windowFrom: Instant,
    @Column(nullable = false) var windowTo: Instant,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var status: DoorScanStatus = DoorScanStatus.BATCH_PENDING_CONFIRM,
    @Column(nullable = false, length = 1000) var ruleDescription: String,
    @Column(nullable = false) var affectedCount: Int = 0,
    @Column(nullable = false) var confirmedCount: Int = 0,
    @Column(nullable = false) var refundedCount: Int = 0,
    @Column(nullable = false) var createdAt: Instant = Instant.now(),
    var resolvedAt: Instant? = null,
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null
)
