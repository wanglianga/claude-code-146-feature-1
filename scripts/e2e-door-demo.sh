#!/usr/bin/env bash
# 门禁打不开的自动补偿 —— 端到端手工验证脚本
# 覆盖：
#   1) 单人确认门禁故障：扫码上报 → 延长+人工放行 → 维修工单 → 回查（单账号留证）
#   2) 整店门禁异常：3 名用户扫码失败 → 确认首人后批量补偿 → 客服逐个确认是否进入
#   3) 走错门店 / 预约未到 / 定位异常：回复引用订单与定位证据，驳回不退款
set -e
BASE=http://localhost:8080
J() { python3 -c "import sys,json; $1"; }
T0=$(python3 -c "import datetime; print((datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0)-datetime.timedelta(minutes=5)).isoformat().replace('+00:00','Z'))")
T1=$(python3 -c "import datetime; print((datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0)+datetime.timedelta(minutes=55)).isoformat().replace('+00:00','Z'))")
# 种子门店（上海）附近坐标 / 北京坐标（定位差异反证）
LAT=31.2305; LNG=121.4738
FAR_LAT=39.9042; FAR_LNG=116.4074

curl -s -X POST "$BASE/api/admin/clock" -H 'Content-Type: application/json' -d '{"time":null}' >/dev/null

mkuser() { # phone name
  curl -s -X POST "$BASE/api/users" -H 'Content-Type: application/json' \
    -d "{\"phone\":\"$1\",\"name\":\"$2\",\"realNameVerified\":true}" | J 'import sys,json; print(json.load(sys.stdin)["id"])'
}
seat_id() { # code
  curl -s "$BASE/api/stores/1/seats" | J "import sys,json; print([s['id'] for s in json.load(sys.stdin) if s['code']=='$1'][0])"
}

echo "================ 场景一：单人门禁故障 → 延长 + 人工放行 + 维修工单 ================"
U1=$(mkuser 13770000001 门禁故障甲)
S1=$(seat_id A01)
BK1=$(curl -s -X POST "$BASE/api/bookings" -H 'Content-Type: application/json' -d "{\"userId\":$U1,\"storeId\":1,\"seatId\":$S1,\"type\":\"SINGLE\",\"startTime\":\"$T0\",\"endTime\":\"$T1\",\"silenceRuleAcknowledged\":true}" | J 'import sys,json; print(json.load(sys.stdin)["bookingNo"])')
curl -s -X POST "$BASE/api/bookings/$BK1/pay" >/dev/null
echo "订单 $BK1 已支付"

DA1=$(curl -s -X POST "$BASE/api/access/scan-denied" -H 'Content-Type: application/json' \
  -d "{\"userId\":$U1,\"storeId\":1,\"errorCode\":\"MECHANICAL_JAM\",\"doorId\":\"GATE-1\",\"clientLat\":$LAT,\"clientLng\":$LNG}" \
  | J 'import sys,json; d=json.load(sys.stdin); print(d["attemptNo"])')
echo "扫码失败记录 $DA1："
curl -s "$BASE/api/support/door-attempts/$DA1" | J 'import sys,json; d=json.load(sys.stdin); print("  初判=",d["classification"],"支付=",d["paymentStatus"],"距门店=",d["locationDistanceMeters"],"m"); print("  回复:",d["suggestedReply"][:80])'

echo "-- 客服确认门禁故障：延长 60 分钟 + 人工通道放行 --"
curl -s -X POST "$BASE/api/support/door-attempts/$DA1/confirm-fault" -H 'Content-Type: application/json' \
  -d '{"compensationType":"EXTEND_TIME","minutes":60,"grantManualEntry":true,"staffNote":"监控确认闸机未开"}' \
  | J 'import sys,json; d=json.load(sys.stdin); print("  设备事件=",d["incidentNo"],"维修单=",d["repairNo"],"人工放行=",d["manualEntry"]["result"],d["manualEntry"]["seatCode"],"整店批次=",d["batchEventNo"])'
curl -s "$BASE/api/bookings/$BK1" | J 'import sys,json; d=json.load(sys.stdin); print("  订单状态=",d["status"],"延长后结束=",d["extendedEndTime"],"延时费=",d["extraChargedAmount"])'
echo "门店维修工单："
curl -s "$BASE/api/repair-tickets?storeId=1" | J 'import sys,json; d=json.load(sys.stdin); r=d[0]; print("  ",r["repairNo"],r["status"],r["description"][:50])'

echo "================ 场景二：整店门禁异常 → 批量补偿 → 逐个核实 ================"
S2=$(seat_id A02); S3=$(seat_id A03)
U2=$(mkuser 13770000002 门禁故障乙)
U3=$(mkuser 13770000003 门禁故障丙)
BK2=$(curl -s -X POST "$BASE/api/bookings" -H 'Content-Type: application/json' -d "{\"userId\":$U2,\"storeId\":1,\"seatId\":$S2,\"type\":\"SINGLE\",\"startTime\":\"$T0\",\"endTime\":\"$T1\",\"silenceRuleAcknowledged\":true}" | J 'import sys,json; print(json.load(sys.stdin)["bookingNo"])')
BK3=$(curl -s -X POST "$BASE/api/bookings" -H 'Content-Type: application/json' -d "{\"userId\":$U3,\"storeId\":1,\"seatId\":$S3,\"type\":\"SINGLE\",\"startTime\":\"$T0\",\"endTime\":\"$T1\",\"silenceRuleAcknowledged\":true}" | J 'import sys,json; print(json.load(sys.stdin)["bookingNo"])')
curl -s -X POST "$BASE/api/bookings/$BK2/pay" >/dev/null; curl -s -X POST "$BASE/api/bookings/$BK3/pay" >/dev/null
DA2=$(curl -s -X POST "$BASE/api/access/scan-denied" -H 'Content-Type: application/json' -d "{\"userId\":$U2,\"storeId\":1,\"errorCode\":\"READER_FAILURE\",\"doorId\":\"GATE-1\",\"clientLat\":$LAT,\"clientLng\":$LNG}" | J 'import sys,json; print(json.load(sys.stdin)["attemptNo"])')
DA3=$(curl -s -X POST "$BASE/api/access/scan-denied" -H 'Content-Type: application/json' -d "{\"userId\":$U3,\"storeId\":1,\"errorCode\":\"READER_FAILURE\",\"doorId\":\"GATE-1\",\"clientLat\":$LAT,\"clientLng\":$LNG}" | J 'import sys,json; print(json.load(sys.stdin)["attemptNo"])')

echo "-- 再确认一条故障记录触发回查（同事件已有 3 名用户，生成整店批次）--"
EV=$(curl -s -X POST "$BASE/api/support/door-attempts/$DA2/confirm-fault" -H 'Content-Type: application/json' \
  -d '{"compensationType":"EXTEND_TIME","minutes":60,"grantManualEntry":true,"staffNote":"同批回查"}' \
  | J 'import sys,json; d=json.load(sys.stdin); print(d["batchEventNo"] or "")')
echo "整店事件批次：$EV"
curl -s "$BASE/api/support/door-batches/$EV" | J 'import sys,json; d=json.load(sys.stdin); print("  状态=",d["status"],"总人数=",d["totalAttempts"],"已进入=",d["enteredCount"],"未进入=",d["notEnteredCount"],"规则=",d["compensationType"],d["compensationMinutes"],"分钟")'

echo "-- 客服逐个确认：丙未实际进入 → 改全额退费关单 --"
curl -s -X POST "$BASE/api/support/door-batches/$EV/attempts/$DA3/entry" -H 'Content-Type: application/json' \
  -d '{"entered":false,"note":"监控未见其进店"}' >/dev/null
curl -s "$BASE/api/bookings/$BK3" | J 'import sys,json; d=json.load(sys.stdin); print("  丙订单=",d["status"],"已退=",d["refundedAmount"])'
curl -s "$BASE/api/support/door-batches/$EV" | J 'import sys,json; d=json.load(sys.stdin); print("  批次状态=",d["status"],"已进入=",d["enteredCount"],"未进入=",d["notEnteredCount"])'

echo "================ 场景三：走错门店 / 预约未到 / 定位异常（留证驳回） ================"
STORE2=$(curl -s "$BASE/api/stores" | J 'import sys,json; print([s["id"] for s in json.load(sys.stdin) if "有人" in s["name"]][0])')
CSEAT=$(curl -s "$BASE/api/stores/$STORE2/seats" | J 'import sys,json; print(json.load(sys.stdin)[0]["id"])')
UW=$(mkuser 13770000004 走错门店)
BKW=$(curl -s -X POST "$BASE/api/bookings" -H 'Content-Type: application/json' -d "{\"userId\":$UW,\"storeId\":$STORE2,\"seatId\":$CSEAT,\"type\":\"SINGLE\",\"startTime\":\"$T0\",\"endTime\":\"$T1\",\"silenceRuleAcknowledged\":true}" | J 'import sys,json; print(json.load(sys.stdin)["bookingNo"])')
curl -s -X POST "$BASE/api/bookings/$BKW/pay" >/dev/null
curl -s -X POST "$BASE/api/access/scan-denied" -H 'Content-Type: application/json' \
  -d "{\"userId\":$UW,\"storeId\":1,\"errorCode\":\"READER_FAILURE\",\"doorId\":\"GATE-1\",\"clientLat\":$LAT,\"clientLng\":$LNG}" \
  | J 'import sys,json; d=json.load(sys.stdin); print("走错门店初判=",d["classification"]); print("  回复:",d["suggestedReply"][:100])'

UE=$(mkuser 13770000005 来得太早)
T2=$(python3 -c "import datetime; print((datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0)+datetime.timedelta(hours=2)).isoformat().replace('+00:00','Z'))")
T3=$(python3 -c "import datetime; print((datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0)+datetime.timedelta(hours=3)).isoformat().replace('+00:00','Z'))")
# 先无订单扫码 → NONE；再下两小时后的已支付订单扫码 → 提前到店（PAID）
curl -s -X POST "$BASE/api/access/scan-denied" -H 'Content-Type: application/json' \
  -d "{\"userId\":$UE,\"storeId\":1,\"errorCode\":\"DEVICE_TIMEOUT\",\"clientLat\":$LAT,\"clientLng\":$LNG}" \
  | J 'import sys,json; d=json.load(sys.stdin); print("无订单初判=",d["classification"],"支付=",d["paymentStatus"])'
BKE=$(curl -s -X POST "$BASE/api/bookings" -H 'Content-Type: application/json' -d "{\"userId\":$UE,\"storeId\":1,\"seatId\":$S1,\"type\":\"SINGLE\",\"startTime\":\"$T2\",\"endTime\":\"$T3\",\"silenceRuleAcknowledged\":true}" | J 'import sys,json; print(json.load(sys.stdin)["bookingNo"])')
curl -s -X POST "$BASE/api/bookings/$BKE/pay" >/dev/null
curl -s -X POST "$BASE/api/access/scan-denied" -H 'Content-Type: application/json' \
  -d "{\"userId\":$UE,\"storeId\":1,\"errorCode\":\"DEVICE_TIMEOUT\",\"clientLat\":$LAT,\"clientLng\":$LNG}" \
  | J 'import sys,json; d=json.load(sys.stdin); print("提前到店初判=",d["classification"],"支付=",d["paymentStatus"]); print("  回复:",d["suggestedReply"][:90])'

UF=$(mkuser 13770000006 定位异常)
BKF=$(curl -s -X POST "$BASE/api/bookings" -H 'Content-Type: application/json' -d "{\"userId\":$UF,\"storeId\":1,\"seatId\":$(seat_id A03),\"type\":\"SINGLE\",\"startTime\":\"$T0\",\"endTime\":\"$T1\",\"silenceRuleAcknowledged\":true}" | J 'import sys,json; print(json.load(sys.stdin)["bookingNo"])')
curl -s -X POST "$BASE/api/bookings/$BKF/pay" >/dev/null
DAF=$(curl -s -X POST "$BASE/api/access/scan-denied" -H 'Content-Type: application/json' \
  -d "{\"userId\":$UF,\"storeId\":1,\"errorCode\":\"UNKNOWN_DEVICE\",\"clientLat\":$FAR_LAT,\"clientLng\":$FAR_LNG}" \
  | J 'import sys,json; d=json.load(sys.stdin); print(d["attemptNo"])')
curl -s "$BASE/api/support/door-attempts/$DAF" | J 'import sys,json; d=json.load(sys.stdin); print("定位异常初判=",d["classification"],"距门店=",d["locationDistanceMeters"],"m")'
curl -s -X POST "$BASE/api/support/door-attempts/$DAF/reject" -H 'Content-Type: application/json' \
  -d '{"note":"定位与门店差异过大，不支持设备故障全额退款"}' \
  | J 'import sys,json; d=json.load(sys.stdin); print("  驳回状态=",d["status"])'
curl -s "$BASE/api/bookings/$BKF" | J 'import sys,json; d=json.load(sys.stdin); print("  已退金额=",d["refundedAmount"],"（应为 0）")'
echo E2E_DOOR_OK
