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
    DENIED_NO_FREE_SEAT,
    /** 扫码门店与有效预约门店不一致（走错门店） */
    DENIED_WRONG_STORE,
    /** 二维码/凭证无效（用户侧凭证问题） */
    DENIED_QR_INVALID
}

enum class IncidentType { DOOR_FAILURE, AC_FAILURE, POWER_OUTAGE, SEAT_DAMAGE, LOCKER_FAILURE }
enum class IncidentStatus { OPEN, RESOLVED }

/** 门禁扫码失败错误码：硬件读头/闸机类故障与账号/凭证类问题必须可区分 */
enum class DoorErrorCode {
    /** 读头无响应（扫码设备硬件故障） */
    READER_NO_RESPONSE,
    /** 闸机不开门（机械/控制器故障） */
    GATE_NOT_OPENING,
    /** 设备离线，无法校验凭证 */
    DEVICE_OFFLINE,
    /** 二维码过期/无效（用户侧凭证问题） */
    INVALID_QRCODE,
    /** 账号无通行权限（预约未支付/未到时间/走错门店等，业务侧拒绝） */
    ACCESS_DENIED,
    /** 未知错误 */
    UNKNOWN
}

/** 扫码失败初判分类，决定后续是设备补偿还是引用订单/定位证据回复 */
enum class DoorScanClassification {
    /** 疑似整店门禁设备故障，进入同店同时段回查 */
    SUSPECTED_DOOR_FAULT,
    /** 已确认为整店门禁异常（同店同时段多人失败），补偿按门店事件批量生成 */
    STORE_WIDE_DOOR_FAULT,
    /** 单个账号异常（仅本人扫码失败），保留日志与定位差异，不自动补偿 */
    SINGLE_ACCOUNT,
    /** 预约时间未到 */
    BOOKING_NOT_STARTED,
    /** 走错门店（扫码门店与预约门店不一致） */
    WRONG_STORE,
    /** 门店停电期间扫码 */
    STORE_BLACKOUT,
    /** 无预约/包月权益 */
    NO_BOOKING
}

/** 扫码记录处理状态 */
enum class DoorScanStatus {
    /** 已记录待客服核实 */
    RECORDED,
    /** 已开客服工单 */
    TICKETED,
    /** 客服确认门禁故障，已补偿（延长/退费） */
    COMPENSATED,
    /** 整店事件批量补偿：已按规则生成补偿，待客服确认用户是否实际进入 */
    BATCH_PENDING_CONFIRM,
    /** 客服确认用户实际进入，批量补偿已生效 */
    BATCH_CONFIRMED_ENTERED,
    /** 客服确认用户未进入，已退还受影响时段费用 */
    BATCH_REFUNDED_NOT_ENTERED,
    /** 批次内全部用户已逐人确认完毕 */
    BATCH_RESOLVED,
    /** 客服核实为用户侧原因（未到时间/走错店/凭证问题），回复证据后关闭 */
    REJECTED_USER_SIDE,
    /** 单账号异常留证关闭（防止借设备故障名义套退） */
    CLOSED_SINGLE_ACCOUNT
}

/** 门禁故障补偿方式 */
enum class CompensationType {
    /** 免费延长本次使用时间（免收延时费） */
    FREE_EXTENSION,
    /** 退还受影响时段费用 */
    AFFECTED_PERIOD_REFUND,
    /** 批量事件下待客服确认实际进入，暂不补偿 */
    PENDING_ENTRY_CONFIRM,
    /** 无需补偿（用户侧原因/单账号异常） */
    NONE
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
