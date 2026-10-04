package com.studyroom.repo

import com.studyroom.domain.*
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

interface AppUserRepository : JpaRepository<AppUser, Long> {
    fun findByPhone(phone: String): AppUser?
}

interface StoreRepository : JpaRepository<Store, Long>

interface SeatRepository : JpaRepository<Seat, Long> {
    fun findByStoreIdAndActiveTrue(storeId: Long): List<Seat>
    fun findByStoreIdAndId(storeId: Long, id: Long): Seat?
}

interface SeatSensorRepository : JpaRepository<SeatSensorReading, Long> {
    fun findBySeatId(seatId: Long): SeatSensorReading?
}

interface MembershipPlanRepository : JpaRepository<MembershipPlan, Long> {
    fun findByStoreIdAndActiveTrue(storeId: Long): List<MembershipPlan>
}

interface BookingRepository : JpaRepository<Booking, Long> {
    fun findByBookingNo(bookingNo: String): Booking?

    /**
     * 与给定时间窗相交、且当前仍占座的预约（已支付待进场 / 在场）。
     * 已完成/取消/爽约的历史订单不再阻塞新预约。
     */
    @Query(
        """
        select b from Booking b
        where b.seat.id = :seatId
          and b.status in (com.studyroom.domain.BookingStatus.CONFIRMED,
                           com.studyroom.domain.BookingStatus.IN_PROGRESS)
          and b.startTime < :endExclusive
          and coalesce(b.extendedEndTime, b.endTime) > :startExclusive
        order by b.startTime
        """
    )
    fun findOverlapping(
        @Param("seatId") seatId: Long,
        @Param("startExclusive") startExclusive: Instant,
        @Param("endExclusive") endExclusive: Instant
    ): List<Booking>

    /** 当前时间可进入的计时类预约（已支付、窗口覆盖当前时刻、尚未进场） */
    @Query(
        """
        select b from Booking b
        where b.user.id = :userId and b.store.id = :storeId
          and b.type <> com.studyroom.domain.BookingType.MONTHLY
          and b.status = com.studyroom.domain.BookingStatus.CONFIRMED
          and b.startTime <= :at and coalesce(b.extendedEndTime, b.endTime) > :at
        order by b.startTime
        """
    )
    fun findEnterableTimed(@Param("userId") userId: Long, @Param("storeId") storeId: Long, @Param("at") at: Instant): List<Booking>

    /** 跨门店：当前时间可进入的计时类预约（用于识别“走错门店”） */
    @Query(
        """
        select b from Booking b
        where b.user.id = :userId
          and b.type <> com.studyroom.domain.BookingType.MONTHLY
          and b.status = com.studyroom.domain.BookingStatus.CONFIRMED
          and b.startTime <= :at and coalesce(b.extendedEndTime, b.endTime) > :at
        order by b.startTime
        """
    )
    fun findEnterableTimedAnyStore(@Param("userId") userId: Long, @Param("at") at: Instant): List<Booking>

    /** 跨门店：当前有效的包月权益（用于识别走到错误门店的包月用户） */
    @Query(
        """
        select b from Booking b
        where b.user.id = :userId
          and b.type = com.studyroom.domain.BookingType.MONTHLY
          and b.status in (com.studyroom.domain.BookingStatus.CONFIRMED, com.studyroom.domain.BookingStatus.IN_PROGRESS)
          and b.startTime <= :at and coalesce(b.extendedEndTime, b.endTime) >= :at
        order by b.startTime
        """
    )
    fun findActiveMembershipsAnyStore(@Param("userId") userId: Long, @Param("at") at: Instant): List<Booking>

    fun findFirstByUserIdAndStoreIdAndStatus(userId: Long, storeId: Long, status: BookingStatus): Booking?

    @Query(
        """
        select b from Booking b
        where b.seat.id = :seatId
          and b.status in (com.studyroom.domain.BookingStatus.CONFIRMED,
                           com.studyroom.domain.BookingStatus.IN_PROGRESS)
          and coalesce(b.extendedEndTime, b.endTime) > :fromTime
        order by b.startTime
        """
    )
    fun findActiveOnSeat(@Param("seatId") seatId: Long, @Param("fromTime") fromTime: Instant): List<Booking>

    fun findByUserIdOrderByStartTimeDesc(userId: Long): List<Booking>

    @Query(
        """
        select b from Booking b
        where b.store.id = :storeId
          and b.type = com.studyroom.domain.BookingType.MONTHLY
          and b.user.id = :userId
          and b.status in (com.studyroom.domain.BookingStatus.CONFIRMED, com.studyroom.domain.BookingStatus.IN_PROGRESS)
          and b.startTime <= :at and coalesce(b.extendedEndTime, b.endTime) >= :at
        """
    )
    fun findActiveMembership(@Param("storeId") storeId: Long, @Param("userId") userId: Long, @Param("at") at: Instant): Booking?

    @Query("select b from Booking b where b.store.id = :storeId and b.startTime >= :from and b.startTime < :to order by b.startTime")
    fun findByStoreAndTimeRange(storeId: Long, from: Instant, to: Instant): List<Booking>

    /** 已到开始时间+宽限仍未进场的已支付预约（用于迟到标记） */
    @Query(
        """
        select b from Booking b
        where b.status = com.studyroom.domain.BookingStatus.CONFIRMED
          and b.type <> com.studyroom.domain.BookingType.MONTHLY
          and b.startTime < :before
        """
    )
    fun findArrivedButNotCheckedIn(@Param("before") before: Instant): List<Booking>

    /** 已过期的包月权益（结束时间已过且不在场内），用于关闭并结算押金 */
    @Query(
        """
        select b from Booking b
        where b.status = com.studyroom.domain.BookingStatus.CONFIRMED
          and b.type = com.studyroom.domain.BookingType.MONTHLY
          and coalesce(b.extendedEndTime, b.endTime) < :now
        """
    )
    fun findExpiredMemberships(@Param("now") now: Instant): List<Booking>
}

interface ChargeRepository : JpaRepository<Charge, Long> {
    fun findByBookingIdOrderByCreatedAtAsc(bookingId: Long): List<Charge>
}

interface RefundRepository : JpaRepository<Refund, Long> {
    fun findByRefundNo(refundNo: String): Refund?
    fun findByBookingIdOrderByCreatedAtDesc(bookingId: Long): List<Refund>
}

interface AccessLogRepository : JpaRepository<AccessLog, Long> {
    fun findByBookingNoOrderByCreatedAtAsc(bookingNo: String): List<AccessLog>
    fun findByStoreIdAndCreatedAtBetweenOrderByCreatedAtAsc(storeId: Long, from: Instant, to: Instant): List<AccessLog>
    fun findByUserIdOrderByCreatedAtDesc(userId: Long): List<AccessLog>
}

interface TimelineEventRepository : JpaRepository<TimelineEvent, Long> {
    fun findByBookingNoOrderByOccurredAtAsc(bookingNo: String): List<TimelineEvent>
    fun findByStoreIdAndOccurredAtBetweenOrderByOccurredAtAsc(storeId: Long, from: Instant, to: Instant): List<TimelineEvent>
}

interface IncidentRepository : JpaRepository<Incident, Long> {
    fun findByIncidentNo(incidentNo: String): Incident?
    fun findByStoreIdOrderByOpenedAtDesc(storeId: Long): List<Incident>
    fun findByStoreIdAndTypeAndStatus(storeId: Long, type: IncidentType, status: IncidentStatus): List<Incident>
    fun countByStoreIdAndStatus(storeId: Long, status: IncidentStatus): Long
    fun countByStoreIdAndOpenedAtBetween(storeId: Long, from: Instant, to: Instant): Long
}

interface ComplaintRepository : JpaRepository<Complaint, Long> {
    fun findByComplaintNo(complaintNo: String): Complaint?
    fun findByStoreIdOrderByCreatedAtDesc(storeId: Long): List<Complaint>
    fun countByStoreIdAndCreatedAtBetween(storeId: Long, from: Instant, to: Instant): Long
}

interface SupportTicketRepository : JpaRepository<SupportTicket, Long> {
    fun findByTicketNo(ticketNo: String): SupportTicket?
    fun findByStoreIdOrderByCreatedAtDesc(storeId: Long): List<SupportTicket>
    fun countByStoreIdAndCauseAndCreatedAtBetween(storeId: Long, cause: DisputeCause, from: Instant, to: Instant): Long
}

interface DoorScanAttemptRepository : JpaRepository<DoorScanAttempt, Long> {
    fun findByAttemptNo(attemptNo: String): DoorScanAttempt?

    /** 同一门店同一时段的扫码失败记录（回查“是否还有其他人扫码失败”） */
    fun findByStoreIdAndScannedAtBetweenOrderByScannedAtAsc(
        storeId: Long, from: Instant, to: Instant
    ): List<DoorScanAttempt>

    fun findByIncidentNo(incidentNo: String): List<DoorScanAttempt>
    fun findByBatchNoOrderByScannedAtAsc(batchNo: String): List<DoorScanAttempt>
    fun findByStoreIdOrderByScannedAtDesc(storeId: Long): List<DoorScanAttempt>
    fun findByUserIdOrderByScannedAtDesc(userId: Long): List<DoorScanAttempt>
}

interface StoreDoorEventRepository : JpaRepository<StoreDoorEvent, Long> {
    fun findByEventNo(eventNo: String): StoreDoorEvent?
    fun findByIncidentNo(incidentNo: String): StoreDoorEvent?
    fun findByStoreIdOrderByOpenedAtDesc(storeId: Long): List<StoreDoorEvent>
}

interface RepairTicketRepository : JpaRepository<RepairTicket, Long> {
    fun findByRepairNo(repairNo: String): RepairTicket?
    fun findByIncidentNo(incidentNo: String): List<RepairTicket>
    fun findByStoreIdOrderByOpenedAtDesc(storeId: Long): List<RepairTicket>
}
