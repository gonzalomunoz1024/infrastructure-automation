B=http://localhost:18081
CSRF=$(curl -s -b jar $B/console/v1/session | python3 -c 'import json,sys;print(json.load(sys.stdin)["csrfToken"])')
H="-H X-Csrf-Token:$CSRF -H Content-Type:application/json"
p(){ python3 -c "import json,sys; d=json.load(sys.stdin); print(json.dumps(d,indent=1)[:${2:-900}])"; }
echo "== discover"; R=$(curl -s -b jar $H -X POST $B/console/v1/discover -d '{"type":"vm"}'); echo "$R" | p x 400
RUN=$(echo "$R" | python3 -c 'import json,sys;print(json.load(sys.stdin).get("runId",""))')
echo "== second discover"; curl -s -w " [%{http_code}]\n" -b jar $H -X POST $B/console/v1/discover -d '{"type":"vm"}'
for i in $(seq 1 20); do S=$(curl -s -b jar $B/console/v1/discover/$RUN | python3 -c 'import json,sys;print(json.load(sys.stdin)["status"])'); [ "$S" != running ] && break; sleep 2; done
echo "== run: $S"; curl -s -b jar $B/console/v1/discover/$RUN | p x 400
echo "== unmanaged mine"; curl -s -b jar "$B/console/v1/unmanaged?type=vm&owner=mine" | p x 1500
echo "== assign vm-1043 to data"; curl -s -w " [%{http_code}]\n" -b jar $H -H "Idempotency-Key: j-assign-1" -X POST $B/console/v1/unmanaged:assign -d '{"type":"vm","providerId":"vm-1043","accountId":"210987654321","note":"looks like ETL"}' | head -c 700; echo
echo "== assign unknown account"; curl -s -w " [%{http_code}]\n" -b jar $H -X POST $B/console/v1/unmanaged:assign -d '{"type":"vm","providerId":"vm-1043","accountId":"999999999999"}'
echo "== findings"; curl -s -w " [%{http_code}]\n" -b jar "$B/console/v1/findings" | head -c 600; echo
echo "== stats"; curl -s -w " [%{http_code}]\n" -b jar "$B/console/v1/findings/stats" | head -c 600; echo
