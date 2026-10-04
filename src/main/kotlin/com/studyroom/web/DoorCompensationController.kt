package com.studyroom.web

import com.studyroom.domain.DoorAttemptStatus
import com.studyroom.service.DoorCompensationCommand
import com.studyroom.service.DoorCompensationService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

@Tag(
    name = "门禁故障自助补偿",
    description = "到店扫码失败上报与初判；客服确认门禁故障后延长/退费、生成维修工单；" +
        "补偿后回查同一时段批量补偿，客服逐个确认是否实际进入；未到时间/走错门店/单账号异常留证驳回"
)
@RestController
@RequestMapping("/api")
@org.springframework.transaction.annotation.Transactional
class DoorCompensationController(private val service: DoorCompensationService) {

    @Operation(
        summary = "用户到店扫码但门禁无法识别/打不开时上报",
        description = "记录扫码时间、门禁错误码、支付状态快照与定位；系统初判为疑似门禁故障/未到时间/走错门店/账号异常，" +
            "回复直接引用订单时间窗、门店与定位证据。疑似故障进入待客服核实队列。"
    )
    @PostMapping("/access/scan-denied")
    fun scanDenied(@RequestBody @Valid req: DoorScanRequest): DoorScanAttemptView =
        Views.of(service.reportScan(req.userId, req.storeId, req.errorCode, req.doorId, req.clientLat, req.clientLng))

    @Operation(summary = "门店扫码失败记录列表，可按处理状态筛选")
    @GetMapping("/support/door-attempts")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    fun listAttempts(
        @RequestParam storeId: Long,
        status: DoorAttemptStatus? = null
    ): List<DoorScanAttemptView> =
        service.listAttempts(storeId, status).map { Views.of(it) }

    @Operation(summary = "扫码失败记录详情（含系统建议回复与全部证据快照）")
    @GetMapping("/support/door-attempts/{attemptNo}")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    fun getAttempt(@PathVariable attemptNo: String): DoorScanAttemptView =
        Views.of(service.getAttempt(attemptNo))

    @Operation(
        summary = "客服确认门禁故障并执行补偿",
        description = "确认后自动打开/复用门禁设备事件、生成门店维修工单；EXTEND_TIME 延长本次使用时间并可经人工通道放行，" +
            "REFUND_FEE 对未进场订单退还受影响时段全部费用并关闭订单。补偿完成后自动回查同一时段其他扫码失败用户，" +
            "达到整店阈值时按门店事件批量生成补偿（返回 batchEventNo 与受影响记录）。"
    )
    @PostMapping("/support/door-attempts/{attemptNo}/confirm-fault")
    fun confirmFault(
        @PathVariable attemptNo: String,
        @RequestBody @Valid req: ConfirmDoorFaultRequest
    ): ConfirmFaultView =
        Views.of(
            service.confirmFault(
                attemptNo,
                DoorCompensationCommand(req.compensationType, req.minutes, req.grantManualEntry, req.staffNote)
            )
        )

    @Operation(
        summary = "客服驳回（预约未到/走错门店/单账号异常）",
        description = "不产生任何退款；扫码日志、错误码、支付状态与定位差异保留，回复引用订单与定位证据，" +
            "防止用户以设备故障名义申请全额退款。"
    )
    @PostMapping("/support/door-attempts/{attemptNo}/reject")
    fun rejectAttempt(
        @PathVariable attemptNo: String,
        @RequestBody @Valid req: RejectDoorAttemptRequest
    ): DoorScanAttemptView =
        Views.of(service.reject(attemptNo, req.note))

    @Operation(summary = "整店门禁事件批次列表")
    @GetMapping("/support/door-batches")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    fun listBatches(@RequestParam storeId: Long): List<StoreDoorEventView> =
        service.listBatches(storeId).map { Views.of(it) }

    @Operation(summary = "整店门禁事件批次详情")
    @GetMapping("/support/door-batches/{eventNo}")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    fun getBatch(@PathVariable eventNo: String): StoreDoorEventView =
        Views.of(service.getBatch(eventNo))

    @Operation(summary = "批次内的扫码失败记录（补偿规则与逐个核实状态）")
    @GetMapping("/support/door-batches/{eventNo}/attempts")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    fun listBatchAttempts(@PathVariable eventNo: String): List<DoorScanAttemptView> =
        service.listBatchAttempts(eventNo).map { Views.of(it) }

    @Operation(
        summary = "客服逐个确认批次内用户是否实际进入",
        description = "entered=true：延长批次补登人工通道放行；退费批次发现实际进入则改判应退金额、差额留证转追缴。" +
            "entered=false：延长批次中未进入者改按受影响时段全额退并关闭订单。全部核实后批次关闭。"
    )
    @PostMapping("/support/door-batches/{eventNo}/attempts/{attemptNo}/entry")
    fun confirmBatchEntry(
        @PathVariable eventNo: String,
        @PathVariable attemptNo: String,
        @RequestBody @Valid req: ConfirmBatchEntryRequest
    ): StoreDoorEventView =
        Views.of(service.confirmBatchEntry(eventNo, attemptNo, req.entered, req.note))
}
