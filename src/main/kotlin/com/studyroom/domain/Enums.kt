package com.studyroom.domain

/** 座位/预约种类：单座、包间、夜间座 */
enum class SeatType { SINGLE, ROOM, NIGHT }

enum class BookingType {
    /** 单座按小时 */
    SINGLE,
    /** 包间按小时 */
    ROOM,
    /** 夜间座（通宵时段一口价） */
    NIGHT,
    /** 包月权益（一段日期内可重复进出） */
    MONTHLY
}

enum class BookingStatus {
    /** 已下单未支付 */
    PENDING_PAYMENT,
    /** 已支付，等待进场 */
    CONFIRMED,
    /** 在场（包月会员单次进场后也是该状态，离场后回到 CONFIRMED） */
    IN_PROGRESS,
    /** 已正常结束（可能含部分退费） */
    COMPLETED,
    /** 开场前取消 / 全退后关闭 */
    CANCELLED,
    /** 超时未到，计为爽约 */
    NO_SHOW
}

/** 账目方向：应收 / 退还（押金抵扣等） */
enum class ChargeType { FEE, DEPOSIT, EXTENSION_FEE, OVERTIME_FEE, DEPOSIT_DEDUCT, COMPENSATION }

enum class RefundReason {
    /** 用户提前离场 */
    EARLY_LEAVE,
    /** 门店责任：停电、空调故障、门禁故障等 */
    STORE_FAULT,
    /** 迟到顺延碰撞导致下一位用户无法使用原座 */
    COLLISION_REASSIGN,
    /** 客服争议判定退款 */
    DISPUTE_APPROVED,
    /** 未消费全额退（取消/爽约免责） */
    UNUSED_FULL,
    /** 押金余额正常退回 */
    DEPOSIT_RETURN
}

enum class RefundStatus { PENDING, COMPLETED, REJECTED }

enum class AccessDirection { IN, OUT }

/** 门禁判定结果——放行与拒绝的原因必须可追溯 */
enum class AccessResult {
    GRANTED,
    /** 进场时发现座位被他人占用，自动换座后放行 */
    GRANTED_REASSIGNED,
    DENIED_NO_BOOKING,
    DENIED_UNPAID,
    DENIED_NOT_REALNAME,
    DENIED_OUTSIDE_WINDOW,
    DENIED_LATE_EXPIRED,
    DENIED_DOOR_FAULT,
    DENIED_STORE_BLACKOUT,
    DENIED_NO_FREE_SEAT
}

enum class IncidentType { DOOR_FAILURE, AC_FAILURE, POWER_OUTAGE, SEAT_DAMAGE, LOCKER_FAILURE }
enum class IncidentStatus { OPEN, RESOLVED }

// ---------------- 门禁打不开自助补偿 ----------------

/** 门禁设备返回的错误码（用户扫码被拒时上报） */
enum class DoorErrorCode {
    /** 闸机通信超时/无响应 */
    DEVICE_TIMEOUT,
    /** 读卡器/二维码模组故障 */
    READER_FAILURE,
    /** 闸机机械故障：识别成功但门不开 */
    MECHANICAL_JAM,
    /** 设备离线 */
    DEVICE_OFFLINE,
    /** 其他设备异常 */
    UNKNOWN_DEVICE
}

/**
 * 扫码失败初判结果：
 * - DOOR_FAULT_SUSPECTED：有有效已支付预约且在进场窗口内 → 疑似门店门禁故障；
 * - EARLY_ARRIVAL：预约开始时间未到（尚在提前进场窗口之外）；
 * - WRONG_STORE：扫码门店与预约门店不一致（有定位证据）；
 * - ACCOUNT_NOT_ELIGIBLE：单账号异常——无有效预约/未支付/未实名，或定位与门店差异过大。
 */
enum class DoorFailureClassification {
    DOOR_FAULT_SUSPECTED,
    EARLY_ARRIVAL,
    WRONG_STORE,
    ACCOUNT_NOT_ELIGIBLE
}

/** 扫码失败处理状态 */
enum class DoorAttemptStatus {
    /** 待客服核实（疑似门禁故障） */
    PENDING,
    /** 客服确认为门禁故障，已补偿 */
    CONFIRMED_FAULT,
    /** 客服/系统驳回（未到时间、走错门店、账号不满足条件） */
    REJECTED,
    /** 纳入整店门禁事件批量补偿 */
    BATCH_COMPENSATED,
    /** 批量补偿后，客服确认该用户经人工通道实际进入 */
    CONFIRMED_ENTERED,
    /** 批量补偿后，客服确认该用户未实际进入（维持退款补偿） */
    CONFIRMED_NOT_ENTERED
}

/** 门禁故障补偿方式 */
enum class CompensationType {
    /** 延长本次使用时间（用户仍要进入） */
    EXTEND_TIME,
    /** 退还受影响时段费用（用户放弃进入） */
    REFUND_FEE
}

/** 门店维修工单状态 */
enum class RepairTicketStatus { OPEN, IN_PROGRESS, RESOLVED }

/** 批量补偿批次状态 */
enum class CompensationBatchStatus {
    /** 已批量补偿，等待客服逐个核实是否实际进入 */
    PENDING_ENTRY_CHECK,
    /** 批次内所有用户均已核实 */
    ALL_CHECKED
}

enum class ComplaintCategory { NOISE_NEIGHBOR, SEAT_OCCUPIED, EQUIPMENT_ENV, OTHER }
enum class ComplaintStatus { OPEN, RESOLVED, REJECTED }

/** 争议根因归类，供运营端筛选 */
enum class DisputeCause {
    /** 设备/门店设施故障（含停电、空调、门禁） */
    DEVICE_FAULT,
    /** 用户迟到及迟到引发的连锁碰撞 */
    USER_LATE,
    /** 包月权益滥用/冲突 */
    PRIVILEGE_ABUSE,
    /** 邻座噪音等用户间纠纷 */
    USER_BEHAVIOR,
    OTHER
}

enum class TicketStatus { OPEN, IN_PROGRESS, RESOLVED, CLOSED }

/** 责任方：门店 / 用户 / 共担 / 无责 */
enum class Responsibility { STORE, USER, SHARED, NONE }

enum class SeatStatus { AVAILABLE, OCCUPIED, MAINTENANCE }

enum class UserRole { USER, STAFF, ADMIN }
