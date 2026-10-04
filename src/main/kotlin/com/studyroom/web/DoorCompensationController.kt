package com.studyroom.web

import com.studyroom.repo.DoorCompensationBatchRepository
import com.studyroom.repo.DoorScanAttemptRepository
import com.studyroom.service.DoorCompensationService
import com.studyroom.service.DoorScanCommand
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*
import java.time.Instant

@Tag(
    name = "门禁打不开自动补偿",
    description = "扫码失败登记（扫码时间/门禁错误码/支付状态/定位差异）、客服确认故障后的延长或退费、" +
        "同店同时段回查整店批量补偿与逐人确认进入、用户侧原因证据回复"
)
@RestController
@RequestMapping("/api")
@org.springframework.transaction.annotation.Transactional
class DoorCompensationController(
    private val doorComp: DoorCompensationService,
    private val scanRepo: DoorScanAttemptRepository,
    private val batchRepo: DoorCompensationBatchRepository
) {
    @Operation(
        summary = "登记门禁扫码失败",
        description = "用户预约开始后到店但门禁无法识别时调用。系统记录扫码时间、门禁错误码、支付状态快照与定位差异，" +
            "硬件类失败且在有效预约窗口内自动开客服工单；门店已有在途门禁维修事件/批量批次时自动并入。"
    )
    @PostMapping("/access/scan-failure")
    fun scanFailure(@RequestBody @Valid req: DoorScanFailureRequest): DoorScanView =
        Views.of(
            doorComp.recordScanFailure(
                DoorScanCommand(req.userId, req.storeId, req.errorCode, req.doorId, req.clientLat, req.clientLon)
            )
        )

    @Operation(summary = "扫码失败记录详情")
    @GetMapping("/door-scans/{scanNo}")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    fun get(@PathVariable scanNo: String): DoorScanView =
        Views.of(scanRepo.findByScanNo(scanNo) ?: throw com.studyroom.support.NotFoundException("扫码记录不存在"))

    @Operation(summary = "按门店+时间窗查扫码失败记录（回查/运营审计）")
    @GetMapping("/door-scans")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    fun list(
        @RequestParam storeId: Long,
        @RequestParam from: Instant,
        @RequestParam to: Instant
    ): List<DoorScanView> =
        scanRepo.findByStoreIdAndScannedAtBetweenOrderByScannedAtAsc(storeId, from, to).map { Views.of(it) }

    @Operation(summary = "按用户查扫码失败记录（单账号异常留证审计）")
    @GetMapping("/door-scans/by-user/{userId}")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    fun byUser(@PathVariable userId: Long): List<DoorScanView> =
        scanRepo.findByUserIdOrderByScannedAtDesc(userId).map { Views.of(it) }

    @Operation(
        summary = "客服确认门禁故障并执行补偿",
        description = "确认后生成门店维修工单；preference=EXTEND 免费延长本次使用时间（与后续已支付订单碰撞时自动回退退费），" +
            "REFUND 直接退还受影响时段费用。补偿完成后自动回查同时段其他扫码失败：存在其他用户则升级整店批量补偿。"
    )
    @PostMapping("/door-scans/{scanNo}/confirm-fault")
    fun confirmFault(
        @PathVariable scanNo: String,
        @RequestBody @Valid req: ConfirmDoorFaultRequest
    ): Map<String, Any> {
        val r = doorComp.confirmDoorFault(
            scanNo, req.preference, req.affectedMinutes, req.staffId, req.note
        )
        return buildMap {
            put("scan", Views.of(r.scan))
            put("storeWide", r.storeWide)
            r.batch?.let { put("batch", Views.of(it)) }
        }
    }

    @Operation(
        summary = "整店批量事件：客服确认某用户是否实际进入",
        description = "实际进入→免费延长等待时长（碰撞回退退费）；未进入→按受影响时段比例退使用费。全部用户确认后批次自动结案。"
    )
    @PostMapping("/door-scans/{scanNo}/confirm-entry")
    fun confirmEntry(
        @PathVariable scanNo: String,
        @RequestBody @Valid req: ConfirmBatchEntryRequest
    ): DoorScanView =
        Views.of(doorComp.confirmBatchEntry(scanNo, req.actuallyEntered, req.enteredAt, req.staffId, req.note))

    @Operation(
        summary = "客服回复用户侧原因（预约未到/走错门店/凭证或无单）",
        description = "回复自动引用订单号、预约门店与时间窗、支付状态及定位距扫码门店距离差；记录长期保留。"
    )
    @PostMapping("/door-scans/{scanNo}/reply")
    fun reply(
        @PathVariable scanNo: String,
        @RequestBody @Valid req: DoorScanReplyRequest
    ): DoorScanView =
        Views.of(doorComp.replyWithEvidence(scanNo, req.note, req.staffId))

    @Operation(summary = "整店补偿批次详情")
    @GetMapping("/door-batches/{batchNo}")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    fun batch(@PathVariable batchNo: String): Map<String, Any> {
        val b = batchRepo.findByBatchNo(batchNo) ?: throw com.studyroom.support.NotFoundException("批次不存在")
        return mapOf(
            "batch" to Views.of(b),
            "members" to scanRepo.findByBatchNoOrderByScannedAtAsc(batchNo).map { Views.of(it) }
        )
    }

    @Operation(summary = "门店整店补偿批次列表")
    @GetMapping("/stores/{storeId}/door-batches")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    fun batches(@PathVariable storeId: Long): List<DoorBatchView> =
        batchRepo.findByStoreIdOrderByCreatedAtDesc(storeId).map { Views.of(it) }
}
