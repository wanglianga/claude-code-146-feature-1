#!/usr/bin/env bash
# 门禁打不开自动补偿全链路演示：
# 扫码留证(时间/错误码/支付状态/定位) → 客服确认故障(免费延长+维修工单) →
# 同店同时段回查 → 整店批量补偿 → 逐人确认进入/未进入退费 →
# 提前到店、走错门店引用订单与定位证据回复 → 单账号异常留证防套退。
set -e
BASE=http://localhost:8080
shift_min() { python3 -c "import datetime; print((datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0)+datetime.timedelta(minutes=$1)).isoformat().replace('+00:00','Z'))"; }
T0=$(shift_min -5)
T1=$(shift_min 115)
curl -s -X POST "$BASE/api/admin/clock" -H 'Content-Type: application/json' -d '{"time":null}' >/dev/null

mkuser() { curl -s -X POST "$BASE/api/users" -H 'Content-Type: application/json' -d "$1" | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])'; }
seatid() { curl -s "$BASE/api/stores/$1/seats" | python3 -c 'import sys,json; print([s["id"] for s in json.load(sys.stdin) if s["code"]=="'$2'"][0])'; }
book_pay() { # userId storeId seatId start end
  curl -s -X POST "$BASE/api/bookings" -H 'Content-Type: application/json' \
    -d "{\"userId\":$1,\"storeId\":$2,\"seatId\":$3,\"type\":\"SINGLE\",\"startTime\":\"$4\",\"endTime\":\"$5\",\"silenceRuleAcknowledged\":true}" \
    | python3 -c 'import sys,json; print(json.load(sys.stdin)["bookingNo"])' \
    | while read bk; do curl -s -X POST "$BASE/api/bookings/$bk/pay" >/dev/null; echo "$bk"; done
}
scan_fail() { # payload
  curl -s -X POST "$BASE/api/access/scan-failure" -H 'Content-Type: application/json' -d "$1"
}

A01=$(seatid 1 A01); A02=$(seatid 1 A02); A03=$(seatid 1 A03); C01=$(seatid 2 C01)

echo "== 1) 甲/乙/丙预约开始后到店，门禁无法识别（扫码留证 + 自动工单）"
U1=$(mkuser '{"phone":"137610000001","name":"门禁补偿-甲","realNameVerified":true}')
U2=$(mkuser '{"phone":"137610000002","name":"门禁补偿-乙","realNameVerified":true}')
U3=$(mkuser '{"phone":"137610000003","name":"门禁补偿-丙","realNameVerified":true}')
B1=$(book_pay $U1 1 $A01 "$T0" "$T1")
B2=$(book_pay $U2 1 $A02 "$T0" "$T1")
B3=$(book_pay $U3 1 $A03 "$T0" "$T1")
S1=$(scan_fail "{\"userId\":$U1,\"storeId\":1,\"errorCode\":\"GATE_NOT_OPENING\",\"doorId\":\"GATE-1\",\"clientLat\":31.2304,\"clientLon\":121.4737}" | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d["scanNo"])')
S2=$(scan_fail "{\"userId\":$U2,\"storeId\":1,\"errorCode\":\"READER_NO_RESPONSE\",\"doorId\":\"GATE-1\"}" | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d["scanNo"])')
S3=$(scan_fail "{\"userId\":$U3,\"storeId\":1,\"errorCode\":\"DEVICE_OFFLINE\",\"doorId\":\"GATE-1\"}" | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d["scanNo"])')
echo "  扫码记录: $S1 / $S2 / $S3，初判均为 SUSPECTED_DOOR_FAULT，甲已自动开工单"

echo "== 2) 客服确认甲属门禁故障 → 免费延长 20 分钟 + 门店维修工单，并回查同时段"
R1=$(curl -s -X POST "$BASE/api/door-scans/$S1/confirm-fault" -H 'Content-Type: application/json' \
  -d '{"preference":"EXTEND","affectedMinutes":20,"staffId":900,"note":"监控确认闸机故障"}')
echo "$R1" | python3 -c 'import sys,json;d=json.load(sys.stdin);print("  甲补偿:",d["scan"]["compensationType"],d["scan"]["compensationMinutes"],"分钟; 整店异常:",d["storeWide"],"; 批次:",(d.get("batch") or {}).get("batchNo"),"; 维修事件:",d["scan"]["incidentNo"])'
curl -s "$BASE/api/bookings/$B1" | python3 -c 'import sys,json;d=json.load(sys.stdin);print("  订单截止时间由",d["endTime"],"免费延长至",d["compEndTime"],"补偿宽限",d["compGraceMinutes"],"分钟")'
BATCH=$(echo "$R1" | python3 -c 'import sys,json;d=json.load(sys.stdin);print((d.get("batch") or {}).get("batchNo") or "")')
INC=$(echo "$R1" | python3 -c 'import sys,json;print(json.load(sys.stdin)["scan"]["incidentNo"])')

echo "== 3) 整店批量：客服逐人确认乙实际进入（等待 8 分钟→免费延长）、丙未进入（退受影响时段费）"
ENTER_T=$(python3 -c "import datetime; print((datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0)+datetime.timedelta(minutes=8)).isoformat().replace('+00:00','Z'))")
curl -s -X POST "$BASE/api/door-scans/$S2/confirm-entry" -H 'Content-Type: application/json' \
  -d "{\"actuallyEntered\":true,\"enteredAt\":\"$ENTER_T\",\"staffId\":900}" \
  | python3 -c 'import sys,json;d=json.load(sys.stdin);print("  乙:",d["status"],d["compensationType"],d["compensationMinutes"],"分钟")'
curl -s -X POST "$BASE/api/door-scans/$S3/confirm-entry" -H 'Content-Type: application/json' \
  -d '{"actuallyEntered":false,"staffId":900,"note":"一直未能进门，离开"}' \
  | python3 -c 'import sys,json;d=json.load(sys.stdin);print("  丙:",d["status"],d["compensationType"],"退款",d["refundAmount"],"元")'
curl -s "$BASE/api/door-batches/$BATCH" | python3 -c 'import sys,json;d=json.load(sys.stdin)["batch"];print("  批次状态:",d["status"],"影响",d["affectedCount"],"人，确认进入",d["confirmedCount"],"退费",d["refundedCount"])'

echo "== 4) 门禁维修完成，受补偿用户可在补偿宽限内进场"
curl -s -X POST "$BASE/api/incidents/$INC/resolve" -H 'Content-Type: application/json' -d '{"resolution":"更换闸机主板"}' >/dev/null
curl -s -X POST "$BASE/api/access/enter" -H 'Content-Type: application/json' -d "{\"userId\":$U1,\"storeId\":1}" \
  | python3 -c 'import sys,json;d=json.load(sys.stdin);print("  甲进场:",d["result"],"-",d["detail"][:60])'

echo "== 5) 预约时间未到：客服回复引用订单与时间窗证据"
U4=$(mkuser '{"phone":"137610000004","name":"提前到店-丁","realNameVerified":true}')
B01=$(seatid 1 B01)
TFUTURE=$(python3 -c "import datetime; print((datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0)+datetime.timedelta(minutes=60)).isoformat().replace('+00:00','Z'))")
B4=$(book_pay $U4 1 $B01 "$TFUTURE" "$(python3 -c "import datetime; print((datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0)+datetime.timedelta(minutes=120)).isoformat().replace('+00:00','Z'))")")
echo "  未来时段订单: $B4（座位 B01，避免与在场订单重叠）"
S4=$(scan_fail "{\"userId\":$U4,\"storeId\":1,\"errorCode\":\"GATE_NOT_OPENING\"}" | python3 -c 'import sys,json;print(json.load(sys.stdin)["scanNo"])')
curl -s -X POST "$BASE/api/door-scans/$S4/reply" -H 'Content-Type: application/json' \
  -d '{"note":"您的预约尚未到可进场时间，请在开始前15分钟内到店扫码","staffId":900}' \
  | python3 -c 'import sys,json;d=json.load(sys.stdin);print("  初判:",d["classification"],"状态:",d["status"]);print("  回复:",d["replyNote"][:180])'

echo "== 6) 走错门店：客服回复引用订单所属门店与定位距离差"
U5=$(mkuser '{"phone":"137610000005","name":"走错门店-戊","realNameVerified":true}')
B5=$(book_pay $U5 2 $C01 "$T0" "$T1")
S5=$(scan_fail "{\"userId\":$U5,\"storeId\":1,\"errorCode\":\"GATE_NOT_OPENING\",\"clientLat\":31.1856,\"clientLon\":121.4127}" | python3 -c 'import sys,json;print(json.load(sys.stdin)["scanNo"])')
curl -s -X POST "$BASE/api/door-scans/$S5/reply" -H 'Content-Type: application/json' \
  -d '{"note":"您的有效预约在科技园有人门店，并非本门店","staffId":900}' \
  | python3 -c 'import sys,json;d=json.load(sys.stdin);print("  初判:",d["classification"],"状态:",d["status"]);print("  证据回复:",d["replyNote"][:200])'

echo "== 7) 单账号异常：无预约+二维码无效，只留扫码日志与定位，不允许自动赔付"
U6=$(mkuser '{"phone":"137610000006","name":"无单硬扫-己","realNameVerified":true}')
S6=$(scan_fail "{\"userId\":$U6,\"storeId\":1,\"errorCode\":\"INVALID_QRCODE\",\"clientLat\":31.2304,\"clientLon\":121.4737}" | python3 -c 'import sys,json;print(json.load(sys.stdin)["scanNo"])')
echo "  拒绝自动赔付接口返回："
curl -s -X POST "$BASE/api/door-scans/$S6/confirm-fault" -H 'Content-Type: application/json' -d '{"preference":"REFUND","staffId":900}' \
  | python3 -c 'import sys,json;d=json.load(sys.stdin);print("  ",d.get("code"),d.get("message"))'
curl -s "$BASE/api/door-scans/by-user/$U6" | python3 -c 'import sys,json;d=json.load(sys.stdin);print("  留证记录:",[(x["scanNo"],x["classification"],x["status"],(round(x["locationDistanceMeters"]) if x["locationDistanceMeters"] is not None else None)) for x in d])'
echo E2E4_OK
