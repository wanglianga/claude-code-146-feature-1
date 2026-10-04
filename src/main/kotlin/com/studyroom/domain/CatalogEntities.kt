package com.studyroom.domain

import jakarta.persistence.*
import java.time.Instant
import java.time.LocalTime

@Entity
@Table(name = "app_user")
class AppUser(
    @Column(nullable = false, unique = true) var phone: String,
    @Column(nullable = false) var name: String,
    @Column(nullable = false) var realNameVerified: Boolean = false,
    var idCardMasked: String? = null,
    /** 累计爽约次数，影响后续预约策略 */
    @Column(nullable = false) var noShowCount: Int = 0,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var role: UserRole = UserRole.USER,
    @Column(nullable = false) var blacklisted: Boolean = false,
    @Column(nullable = false) var createdAt: Instant = Instant.now(),
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null
)

@Entity
@Table(name = "store")
class Store(
    @Column(nullable = false) var name: String,
    @Column(nullable = false) var address: String = "",
    /** 无人值守门店 */
    @Column(nullable = false) var unattended: Boolean = true,
    /** 临时停电标志：为 true 时门禁拒绝新进入 */
    @Column(nullable = false) var blackout: Boolean = false,
    var openTime: LocalTime? = null,
    var closeTime: LocalTime? = null,
    /** 迟到宽限分钟数 */
    @Column(nullable = false) var lateGraceMinutes: Int = 15,
    /** 提前进场窗口分钟数 */
    @Column(nullable = false) var earlyEntryMinutes: Int = 15,
    /** 全局价格倍率，运营调整后影响新预约价格 */
    @Column(nullable = false) var priceMultiplier: Double = 1.0,
    /** 提前离场退费比例（针对剩余时长部分） */
    @Column(nullable = false) var earlyLeaveRefundRate: Double = 0.5,
    /** 超时加价倍率 */
    @Column(nullable = false) var overtimeRate: Double = 1.5,
    /** 门店定位（纬度/经度），用于核对扫码人是否真的到店 */
    var latitude: Double? = null,
    var longitude: Double? = null,
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null
)

@Entity
@Table(
    name = "seat",
    uniqueConstraints = [UniqueConstraint(columnNames = ["store_id", "seat_code"])]
)
class Seat(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id")
    var store: Store,
    @Column(name = "seat_code", nullable = false) var code: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var seatType: SeatType,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var status: SeatStatus = SeatStatus.AVAILABLE,
    /** 基础每小时价（夜间座为夜间时段一口价） */
    @Column(nullable = false) var hourlyPrice: java.math.BigDecimal,
    @Column(nullable = false) var lockerAvailable: Boolean = false,
    /** 所属区域，便于布局调整 */
    @Column(nullable = false) var zone: String = "A",
    @Column(nullable = false) var active: Boolean = true,
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null
)

/** 座位传感器最近读数：门店无人值守时判断“座位被占”的依据 */
@Entity
@Table(name = "seat_sensor_reading")
class SeatSensorReading(
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seat_id", unique = true)
    var seat: Seat,
    @Column(nullable = false) var occupied: Boolean = false,
    /** 当前占用者预约号（传感器/店员上报），无人占用时为空 */
    var occupiedByBookingNo: String? = null,
    @Column(nullable = false) var updatedAt: Instant = Instant.now(),
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null
)

/** 包月套餐定义（门店可调整包月规则） */
@Entity
@Table(name = "membership_plan")
class MembershipPlan(
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id")
    var store: Store,
    @Column(nullable = false) var name: String,
    /** 适用座位类型，null 表示全门店通用 */
    @Enumerated(EnumType.STRING) var seatType: SeatType? = null,
    @Column(nullable = false) var price: java.math.BigDecimal,
    @Column(nullable = false) var deposit: java.math.BigDecimal = java.math.BigDecimal.ZERO,
    @Column(nullable = false) var days: Int = 30,
    /** 包月用户固定座位；false 时为浮动权益（任意同类型座位） */
    @Column(nullable = false) var fixedSeat: Boolean = true,
    @Column(nullable = false) var active: Boolean = true,
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Long? = null
)
