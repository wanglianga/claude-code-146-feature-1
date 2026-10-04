# 城市共享自习室：预约 / 门禁延时与退费服务（后端）

Kotlin + Spring Boot 3 + JPA(H2/MySQL) + Redis 实现。覆盖单座、包间、夜间座、包月权益四类预约，
把**预约、支付、门禁、座位状态、延时/顺延、投诉、设备事件、计费退款、客服工单、运营指标**收敛到同一条
时间轴上，重点解决无人值守门店的连续事件一致性问题。

- Swagger UI：<http://localhost:8080/swagger-ui.html>
- OpenAPI JSON：<http://localhost:8080/v3/api-docs>
- 健康检查：<http://localhost:8080/actuator/health>

---

## 1. 业务能力

| 领域 | 能力 |
|---|---|
| 预约 | 单座按小时、包间按小时、夜间座一口价、包月（固定座 / 浮动权益）；记录门店、座位、时间、价格、押金、储物柜、静音规则确认 |
| 支付/押金 | 模拟支付（费用+押金）；取消未消费全退；爽约退押金；储物柜/设备损坏从押金扣赔 |
| 门禁进场 | 按 预约 → 支付 → 实名 → 时间窗（提前进场/迟到宽限）→ 门店停电 → 门禁故障 顺序判定；全部判定写门禁日志与监控事件编号 |
| 门禁离场 | 记录实际离场、是否延时/超时、储物柜与座位设备状态；正常/提前离场/门店责任三种结算 |
| 门禁打不开自动补偿 | 到店扫码失败记录扫码时间、错误码、支付状态、定位；确认门禁故障后**延长使用时间（人工通道放行）或退还受影响时段费用**并生成门店维修工单；补偿后回查同一时段，达阈值按**整店门禁事件批量补偿**，客服只逐个确认是否实际进入；预约未到/走错门店/单账号异常回复引用订单与定位证据并留证驳回 |
| 迟到顺延 | 宽限期内迟到可申请顺延（结束时间后移并计延时费），系统立即对后续订单做**碰撞预警** |
| 临时延时 | 进行中订单可延时；下一位已在场则拒绝；下一位已支付未进场则预警并在其进场时自动换座 |
| 连续事件 | **晚到 → 顺延 → 占下一位座位 → 下一位进场自动换座（GRANTED_REASSIGNED）→ 投诉自动归因 USER_LATE 并开工单** |
| 设备/环境 | 门禁打不开、空调故障、座位/储物柜损坏、门店临时停电；停电强制按门店责任清场结算，期间禁止进入 |
| 投诉 | 邻座噪音、座位被占、设备环境等；自动归因 `DEVICE_FAULT / USER_LATE / PRIVILEGE_ABUSE / USER_BEHAVIOR / OTHER`，同步生成客服工单 |
| 客服工单 | 证据包聚合：预约、门禁日志、统一时间轴、座位传感器读数、退款记录、设备事件、监控编号、用户申诉、店员处理记录；责任判定（门店/用户/共担）与退款同事务结案 |
| 运营 | 上座率、延时率、投诉率、设备故障数、争议根因分布；按门店筛出“设备故障 vs 用户迟到/权益滥用”；调价倍率、座位布局、包月规则调整 |

## 2. 一致性设计（本服务的核心）

1. **放行即开始**：`/api/access/enter` 在一个事务内完成门禁判定、订单置 `IN_PROGRESS`、记录实际进场、
   写 Redis 座位占用与传感器状态——不会出现“门禁已放行但订单仍未开始”。
2. **退费即释放**：`/api/access/leave`（及停电强制清场）在一个事务内完成结算退款、押金退还、
   订单终态、Redis 占用释放、传感器清空——不会出现“退费完成但座位仍锁定”。
3. **座位临界区**：进/离场、自动换座在 Redis 短租锁 `seat:lock:{seatId}` 内执行；
   实时占用 `seat:state:{seatId}` 带 TTL，释放时校验占用者本人，防止误放他人座位。
4. **碰撞前置预警**：延时时即查出后续已支付订单并在双方时间轴上记录 `COLLISION_PREDICTED`；
   下一位进场若发现物理占用（Redis 或传感器），按同类型、同价或更低价稳定挑选备用座位自动换座。
5. **统一时间轴**：所有状态变化都追加不可变事件（`TimelineEvent`），按预约或门店+时间回放；
   每条门禁日志带确定性监控事件编号（`门店-闸机-结果-时间戳`）。
6. **退款上限保护**：任何退款不得超过 `已付 - 已退 - 押金扣赔`，防止重复赔付。
7. **门禁故障补偿闭环**：扫码失败先落证据（扫码时间、错误码、支付快照、定位距离）再初判；
   确认故障后“延长+人工通道放行”与订单置 `IN_PROGRESS`、Redis 占用写入同事务，
   “退费”与订单关闭同事务（复用放行即开始/退费即释放不变量），并同步开设备事件与门店维修工单。
8. **整店事件批量判定**：单次确认补偿后回查前后 30 分钟同窗扫码失败，去重用户 ≥3 判定整店门禁异常，
   按门店事件批量套用同一补偿规则；早先逐单补偿者回溯纳入不重复赔付，客服只逐个确认是否实际进入。
9. **反欺诈留证**：未到进场时间 / 走错门店 / 未支付 / 未实名 / 定位距店 >300m 一律不补偿，
   扫码日志、错误码、支付快照、定位差异永久保留；批量退费后被监控证实“实际进入”的，
   系统只改判应退金额并把差额转人工追缴（不自动扣款、不冲正流水），防止借设备故障名义套全额退款。

## 3. 工程结构

```
src/main/kotlin/com/studyroom
├── domain/      枚举与 JPA 实体（门店/座位/传感器/套餐/预约/账目/退款/门禁/时间轴/事件/投诉/工单/
│                门禁扫码失败记录/整店门禁补偿事件/门店维修工单）
├── repo/        Spring Data 仓储（含时段重叠、可进场预约、在场订单、跨门店有效预约、同窗扫码失败等查询）
├── support/     全局异常、Redis 座位锁、可覆写业务时钟
├── service/     BookingService / AccessService / BillingService / IncidentService
│                ComplaintService / SupportService / OperationsService / SensorService
│                DoorCompensationService（门禁打不开自动补偿与整店批量判定）
├── web/         REST 控制器与 DTO/视图映射（含 DoorCompensationController / RepairTicketController）
└── config/      OpenAPI 配置、启动种子数据
src/test/kotlin/com/studyroom/it   20 个端到端集成测试（自动拉起独立端口 Redis）
scripts/         三个 curl 版全链路演示脚本
```

## 4. 本地启动

### 方式 A：Docker Compose（一键，推荐）

```bash
docker compose up --build
# 应用：http://localhost:8080 ，Redis：6379，默认 H2 内存库
```

需要 MySQL 持久化时：

```bash
docker compose --profile mysql up --build
# 应用在 8081；MySQL 8.4 在 3306（studyroom/studyroom）
```

### 方式 B：本机直接运行

前置：JDK 17、Redis 7（`redis-server` 在 PATH 中）。

```bash
# 1) 启动 Redis
redis-server --port 6379 --daemonize yes --save "" --appendonly no

# 2) 启动应用（Gradle Wrapper 会自动下载 Gradle 8.10.2）
./gradlew bootRun
# 或先打包再运行
./gradlew bootJar
java -jar build/libs/studyroom-booking-1.0.0.jar
```

环境变量：`REDIS_HOST`(默认 localhost)、`REDIS_PORT`(6379)、
`DB_URL/DB_DRIVER/DB_USERNAME/DB_PASSWORD`；使用 MySQL 时加 `SPRING_PROFILES_ACTIVE=mysql`。

## 5. 测试账号（启动种子数据自动创建）

| 手机号 | 角色 | 实名 | 用途 |
|---|---|---|---|
| 13800000001 | 普通用户 小明 | 是 | 正常/迟到/顺延场景 |
| 13800000002 | 普通用户 小红 | 是 | “下一位用户”/被换座/投诉 |
| 13800000003 | 普通用户 小强 | **否** | 验证未实名门禁拒绝 |
| 13900000000 | 运营管理员 | 是 | 运营/客服操作（本版接口未做登录拦截，角色仅为数据标记） |

种子门店：`24h 无人值守旗舰店`（迟到宽限 15 分钟、提前进场 15 分钟、提前离场退 50% 剩余、超时 1.5 倍），
座位 A01/A02/A03（单座 10 元/h，A02 带储物柜）、B01、R01（包间）、N01（夜间座），
包月套餐含“固定单座包月”与“浮动单座包月”。

> 说明：本服务聚焦后端一致性，未实现登录鉴权；接口中的 `userId/storeId` 由调用方直接传入，
> 便于演示与自动化验证。生产接入时在网关层绑定实名身份即可，门禁判定逻辑无需改动。

## 6. 验证方式

### 6.1 自动化测试（已实际执行）

```bash
./gradlew test
```

测试会自动在 **6390 端口**启动独立 `redis-server`（db15，与本地 6379 隔离）。
覆盖 20 个用例、6 个测试类：

- `NormalFlowIT`：支付→放行→订单 IN_PROGRESS→离场退押金释放；储物柜未清空扣 30 元押金
- `CollisionChainIT`：晚到 10 分钟→顺延 40 分钟→碰撞预警→B 进场自动换座 A01→A02→
  投诉归因 USER_LATE→双离场座位全释放；超宽限迟到拒绝 + 爽约扫描退押金
- `ExceptionalScenariosIT`：门禁故障拒绝并带事件编号；停电强制清场全额退剩余费用+押金且禁入；提前离场部分退费
- `MembershipIT`：固定座包月多次进出（权益保留、座位释放、押金不重复退）；到期包月权益扫描关闭并退押金；重叠包月权益拒绝；浮动包月现场分座
- `SupportAndOpsIT`：空调故障投诉自动归因 DEVICE_FAULT、证据包、申诉、门店责任退款结案、运营指标
- `DoorCompensationIT`：**门禁打不开自动补偿**——确认故障延长 60 分钟+人工通道放行+维修工单+回查单账号留证；
  未进场全额退使用费押金并关单；整店异常批量补偿与逐个确认进入/未进入；退费批次发现实际进入的改判追缴留证；
  先逐单后批量的回溯纳入（不重复赔付）；批次成立后第 4 人单独确认仍计入同一门店事件；
  走错门店/预约未到引用订单与定位证据驳回；定位千里之外/未支付/无预约留证不退

最近一次执行结果：**20 tests, 0 failures / 0 errors**（`./gradlew test` BUILD SUCCESSFUL）。

> 受限内存环境（容器 cgroup 约 1.5GB）可加参数运行，避免 Gradle/测试 JVM 被 OOM 杀掉：
> `GRADLE_OPTS="-Xmx512m" ./gradlew test --no-daemon -Dorg.gradle.jvmargs=-Xmx512m --max-workers=1`

### 6.2 HTTP 全链路手工验证（已实际执行）

先启动应用（方式 A 或 B），然后：

```bash
# 场景一：晚到 → 顺延 → 占下一座位 → 自动换座 → 投诉工单 → 退款 → 释放 → 运营分类
./scripts/e2e-collision-demo.sh

# 场景二：停电强制清场退费释放、停电禁入、空调故障投诉归因 DEVICE_FAULT、运营指标
./scripts/e2e-incident-demo.sh

# 场景三：门禁打不开自动补偿（延长+人工放行/退费、维修工单、整店批量补偿、走错门店/未到/定位异常留证）
./scripts/e2e-door-demo.sh
```

> 演示脚本依赖新内存库；若应用里已跑过其他数据，重启应用即可（默认 H2 内存模式）。
> 为便于复现“未来时段”的预约，系统提供演示用时钟覆写接口
> `POST /api/admin/clock {"time":"2026-10-03T20:00:00Z"}`，传 `{"time":null}` 恢复真实时间。

### 6.3 接口速查（均在 Swagger UI 中可直接调用）

- 预约：`POST /api/bookings`、`/pay`、`/cancel`、`/{no}/postpone`、`/{no}/extend`、`GET /{no}`、`/{no}/timeline`
- 门禁：`POST /api/access/enter`、`POST /api/access/leave`、`POST /api/sensors/report`、`POST /api/access/scan-denied`
- 门禁补偿：`GET /api/support/door-attempts`、`GET /api/support/door-attempts/{no}`、`POST /{no}/confirm-fault`、`POST /{no}/reject`、
  `GET /api/support/door-batches`、`GET /api/support/door-batches/{no}`、`GET /{no}/attempts`、`POST /{no}/attempts/{attemptNo}/entry`
- 门店维修：`GET /api/repair-tickets`、`GET /api/repair-tickets/{no}`、`POST /api/repair-tickets/{no}/resolve`
- 门店/设备：`POST /api/incidents`、`POST /api/stores/{id}/blackout`、`POST /api/stores/no-show-scan`
- 投诉/工单：`POST /api/complaints`、`GET /api/tickets/{no}/evidence`、`POST /api/tickets/{no}/appeal`、`POST /api/tickets/{no}/decide`
- 运营：`GET /api/ops/stores/{id}/metrics`、`GET /api/ops/stores/{id}/disputes`、
  `POST /api/ops/stores/{id}/price`、`POST /api/ops/stores/{id}/seats/{seatId}`、`POST /api/ops/plans/{planId}`

### 6.4 最小手工调用示例

```bash
# 下单（必须确认静音规则）→ 支付 → 进场 → 提前离场退费
curl -s -X POST localhost:8080/api/bookings -H 'Content-Type: application/json' -d '{
  "userId":1,"storeId":1,"seatId":1,"type":"SINGLE",
  "startTime":"2026-10-03T20:00:00Z","endTime":"2026-10-03T22:00:00Z",
  "silenceRuleAcknowledged":true}'
curl -s -X POST localhost:8080/api/bookings/<bookingNo>/pay
curl -s -X POST localhost:8080/api/access/enter -H 'Content-Type: application/json' \
  -d '{"userId":1,"storeId":1}'
curl -s -X POST localhost:8080/api/access/leave -H 'Content-Type: application/json' \
  -d '{"userId":1,"storeId":1,"mode":"EARLY_LEAVE"}'
```

## 7. 本地开发说明

- 构建：`./gradlew build`（编译 + 测试）
- 代码风格：Kotlin 2.0，JDK 17；`open-in-view=false`，控制器读接口显式声明只读事务
- 数据层默认 H2 内存库（MySQL 兼容模式），表结构由 Hibernate `ddl-auto=update` 维护；
  生产建议切换 MySQL 8（compose 已提供 profile）并引入迁移工具
- 仓库默认配置了阿里云 Maven 镜像以提升依赖下载成功率，不影响官方 Maven Central 解析
