package com.studyroom.config

import com.studyroom.domain.*
import com.studyroom.repo.*
import org.slf4j.LoggerFactory
import org.springframework.boot.CommandLineRunner
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalTime

/** 演示种子数据：门店、座位、包月套餐与测试用户（含已实名用户） */
@Component
class DataSeeder(
    private val userRepo: AppUserRepository,
    private val storeRepo: StoreRepository,
    private val seatRepo: SeatRepository,
    private val sensorRepo: SeatSensorRepository,
    private val planRepo: MembershipPlanRepository
) : CommandLineRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    override fun run(vararg args: String?) {
        if (storeRepo.count() > 0) {
            log.info("种子数据已存在，跳过")
            return
        }

        val store1 = storeRepo.save(
            Store(
                name = "24h 无人值守旗舰店",
                address = "市中心 A 座 3 层",
                unattended = true,
                openTime = LocalTime.of(0, 0),
                closeTime = LocalTime.of(23, 59),
                lateGraceMinutes = 15,
                earlyEntryMinutes = 15,
                earlyLeaveRefundRate = 0.5,
                overtimeRate = 1.5,
                latitude = 31.2304,
                longitude = 121.4737
            )
        )
        val store2 = storeRepo.save(
            Store(
                name = "科技园有人门店",
                address = "科技园 B 栋 1 层",
                unattended = false,
                lateGraceMinutes = 20,
                earlyEntryMinutes = 10,
                earlyLeaveRefundRate = 0.4,
                overtimeRate = 1.5,
                latitude = 31.2000,
                longitude = 121.4000
            )
        )

        val seats1 = listOf(
            seat(store1, "A01", SeatType.SINGLE, "10", zone = "静音区A"),
            seat(store1, "A02", SeatType.SINGLE, "10", zone = "静音区A", locker = true),
            seat(store1, "A03", SeatType.SINGLE, "10", zone = "静音区A"),
            seat(store1, "B01", SeatType.SINGLE, "12", zone = "轻音区B"),
            seat(store1, "R01", SeatType.ROOM, "40", zone = "包间区", locker = true),
            seat(store1, "N01", SeatType.NIGHT, "39", zone = "夜间区")
        ).map {
            val s = seatRepo.save(it)
            sensorRepo.save(SeatSensorReading(seat = s, occupied = false, occupiedByBookingNo = null))
            s
        }
        val seats2 = listOf(
            seat(store2, "C01", SeatType.SINGLE, "8", zone = "普通区"),
            seat(store2, "C02", SeatType.SINGLE, "8", zone = "普通区", locker = true)
        ).map {
            val s = seatRepo.save(it)
            sensorRepo.save(SeatSensorReading(seat = s, occupied = false, occupiedByBookingNo = null))
            s
        }

        planRepo.save(
            MembershipPlan(
                store = store1, name = "固定单座包月（静音区）",
                seatType = SeatType.SINGLE, price = BigDecimal("399"),
                deposit = BigDecimal("50"), days = 30, fixedSeat = true
            )
        )
        planRepo.save(
            MembershipPlan(
                store = store1, name = "浮动单座包月",
                seatType = SeatType.SINGLE, price = BigDecimal("299"),
                deposit = BigDecimal.ZERO, days = 30, fixedSeat = false
            )
        )
        planRepo.save(
            MembershipPlan(
                store = store2, name = "科技园单座包月",
                seatType = SeatType.SINGLE, price = BigDecimal("259"),
                deposit = BigDecimal.ZERO, days = 30, fixedSeat = false
            )
        )

        userRepo.save(AppUser(phone = "13800000001", name = "小明(已实名)", realNameVerified = true, idCardMasked = "ID****0001"))
        userRepo.save(AppUser(phone = "13800000002", name = "小红(已实名)", realNameVerified = true, idCardMasked = "ID****0002"))
        userRepo.save(AppUser(phone = "13800000003", name = "小强(未实名)", realNameVerified = false))
        userRepo.save(AppUser(phone = "13900000000", name = "门店运营管理员", realNameVerified = true, role = UserRole.ADMIN))

        log.info("种子数据完成：门店 {}、座位 {}、套餐 3、用户 4", listOf(store1, store2).size, seats1.size + seats2.size)
    }

    private fun seat(
        store: Store, code: String, type: SeatType, price: String,
        zone: String, locker: Boolean = false
    ) = Seat(
        store = store, code = code, seatType = type,
        status = SeatStatus.AVAILABLE,
        hourlyPrice = BigDecimal(price),
        lockerAvailable = locker, zone = zone
    )
}
