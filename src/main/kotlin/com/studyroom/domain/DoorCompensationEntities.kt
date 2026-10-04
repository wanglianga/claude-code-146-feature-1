package com.studyroom.domain

import jakarta.persistence.*
import java.time.Instant

/**
 * 用户到店扫码但门禁无法识别/打不开的记录。
 *
 * 无论最终判定是门店门禁故障、用户提前到店、走错门店还是单账号异常，
 * 扫码时间、门禁错误码、支付状态快照、定位证据都必须保留：
 * 它既是自动补偿的输入，也是防止用户以“设备故障”名义套全额退款的反证。
 */
@Entity
@Table(
    name = "door_scan_attempt",
    indexes = [
        Index(name = "idx_door_scan_store_time", columnList = "store_id,scannedAt"),
        Index(name = "idx_door_scan_user", columnList = "user_id"),
        Index(name = "idx_door_scan_booking", columnList = "bookingNo"),
        Index(name = "idx_door_scan_incident", columnList = "incidentNo"),
        Index(name = "idx_door_scan_batch", columnList = "batch_no"),
        Index(name = "idx_door_scan_status", columnList = "status")
    ]
)
class DoorScanAttempt(
    @Column(nullable = false, unique = true) var attemptNo: String,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    var user: AppUser,

    /** 实际扫码的门店 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id")
    var store: Store,

    /** 系统匹配到的候选预约号（可能属于别的门店），无候选时为空 */
    var bookingNo: String? = null,
    /** 候选预约所属门店（走错门店时与 store 不同，是核心证据） */
    var bookingStoreId: Long? = null,
    /** 预约座位号，仅用于回复引用 */
    var bookingSeatCode: String? = null,

    var doorId: String? = null,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var errorCode: DoorErrorCode,

    /** 扫码时刻（用户声称的“到店时间”以此为准） */
    @Column(nullable = false) var scannedAt: Instant,

    /** 扫码设备上报的定位，可空（未授权定位时仍允许上报，只是证据力下降） */
    var clientLat: Double? = null,
    var clientLng: Double? = null,
    /** 扫码定位与门店定位的直线距离（米），门店未录入坐标时为空 */
    var locationDistanceMeters: Double? = null,

    /** 支付状态快照：PAID / UNPAID / NONE（无预约），原样留证 */
    @Column(nullable = false) var paymentStatus: String,
    /** 预约时间窗快照，便于回复直接引用 */
    var bookingStart: Instant? = null,
    var bookingEnd: Instant? = null,

    @Enumerated(EnumType.STRING) @Column(nullable = false)
    var classification: DoorFailureClassification,
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    var status: DoorAttemptStatus = DoorAttemptStatus.PENDING,

    /** 系统生成的客服回复/判定说明，引用订单号与定位证据 */
    @Column(nullable = false, length = 1000) var suggestedReply: String,
    var monitorEventNo: String? = null,

    /** 确认门禁故障后关联的设备事件 */
    var incidentNo: String? = null,
    /** 纳入整店批量补偿时的批次号 */
    @Column(name = "batch_no") var batchNo: String? = null,
    /** 客服介入后生成的客服工单号 */
    var supportTicketNo: String? = null,

    @Enumerated(EnumType.STRING) var compensationType: CompensationType? = null,
    /** 延长补偿的分钟数（EXTEND_TIME） */
    var compensationMinutes: Long? = null,
    /** 实际退款金额（REFUND_FEE），由退款流水回填 */
    var compensatedAmount: java.math.BigDecimal? = null,

    /** 客服处理备注 */
    @Column(length = 1000) var staffNote: String? = null,
    /** 客服是否核实该用户经人工通道实际进入（仅批量补偿后需要逐个确认） */
    var actualEntered: Boolean? = null,

    @Column(nullable = false) var createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
    var resolvedAt: Instant? = null,
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null
)

/**
 * 整店门禁异常事件：一次确认的门店门禁故障聚合同一时段所有扫码失败的用户，
 * 补偿规则按事件批量生成，客服只需逐个确认用户是否实际进入。
 */
@Entity
@Table(
    name = "store_door_event",
    indexes = [
        Index(name = "idx_door_event_store", columnList = "store_id,openedAt"),
        Index(name = "idx_door_event_incident", columnList = "incidentNo"),
        Index(name = "idx_door_event_status", columnList = "status")
    ]
)
class StoreDoorEvent(
    @Column(nullable = false, unique = true) var eventNo: String,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id")
    var store: Store,

    /** 关联的门禁故障设备事件（Incident，DOOR_FAILURE） */
    @Column(nullable = false) var incidentNo: String,
    var doorId: String? = null,

    /** 故障影响时段（由批次内扫码时间取最小/最大） */
    @Column(nullable = false) var windowFrom: Instant,
    @Column(nullable = false) var windowTo: Instant,

    @Enumerated(EnumType.STRING) @Column(nullable = false)
    var status: CompensationBatchStatus = CompensationBatchStatus.PENDING_ENTRY_CHECK,

    /** 本批次统一采用的补偿规则 */
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    var compensationType: CompensationType,
    @Column(nullable = false) var compensationMinutes: Long = 0,

    @Column(nullable = false, length = 1000) var description: String,
    var repairTicketNo: String? = null,

    @Column(nullable = false) var totalAttempts: Int = 0,
    @Column(nullable = false) var enteredCount: Int = 0,
    @Column(nullable = false) var notEnteredCount: Int = 0,

    @Column(nullable = false) var openedBy: String = "STAFF",
    @Column(nullable = false) var openedAt: Instant = Instant.now(),
    var allCheckedAt: Instant? = null,
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null
)

/** 门店维修工单：确认门禁故障后自动生成，驱动线下维修闭环 */
@Entity
@Table(
    name = "repair_ticket",
    indexes = [
        Index(name = "idx_repair_store", columnList = "store_id,openedAt"),
        Index(name = "idx_repair_incident", columnList = "incidentNo")
    ]
)
class RepairTicket(
    @Column(nullable = false, unique = true) var repairNo: String,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id")
    var store: Store,

    @Column(nullable = false) var incidentNo: String,
    var doorId: String? = null,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var type: IncidentType = IncidentType.DOOR_FAILURE,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var status: RepairTicketStatus = RepairTicketStatus.OPEN,

    @Column(nullable = false, length = 1000) var description: String,
    var assignee: String? = null,
    @Column(nullable = false) var openedBy: String = "SYSTEM",
    @Column(nullable = false) var openedAt: Instant = Instant.now(),
    var resolvedAt: Instant? = null,
    var resolution: String? = null,
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null
)
