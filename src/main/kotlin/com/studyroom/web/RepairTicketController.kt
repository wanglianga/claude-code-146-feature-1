package com.studyroom.web

import com.studyroom.service.DoorCompensationService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

@Tag(name = "门店维修工单", description = "确认门禁故障后自动生成，登记处理人并可联动关闭设备事件恢复门禁")
@RestController
@RequestMapping("/api/repair-tickets")
@org.springframework.transaction.annotation.Transactional
class RepairTicketController(private val service: DoorCompensationService) {

    @Operation(summary = "门店维修工单列表")
    @GetMapping
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    fun list(@RequestParam storeId: Long): List<RepairTicketView> =
        service.listRepairTickets(storeId).map { Views.of(it) }

    @Operation(summary = "维修工单详情")
    @GetMapping("/{repairNo}")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    fun get(@PathVariable repairNo: String): RepairTicketView =
        Views.of(service.getRepairTicket(repairNo))

    @Operation(
        summary = "完成维修工单",
        description = "记录维修结论与处理人；resolveIncident=true 时同步关闭关联门禁设备事件，门禁恢复正常放行。"
    )
    @PostMapping("/{repairNo}/resolve")
    fun resolve(@PathVariable repairNo: String, @RequestBody @Valid req: ResolveRepairRequest): RepairTicketView =
        Views.of(service.resolveRepairTicket(repairNo, req.resolution, req.assignee, req.resolveIncident))
}
