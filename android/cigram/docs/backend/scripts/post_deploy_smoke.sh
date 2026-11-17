#!/bin/bash
# Run AFTER deploying the 19.0 Workers. Real requests against the real Workers; nothing is simulated.
#   usage: ADMIN_TOKEN=<owner token> ./scripts/post_deploy_smoke.sh
# Prints PASS / FAIL per check and a summary of what is still missing (FCM, backup bucket, auth path). It never prints the token.
ADMIN_API=${ADMIN_API:-https://cigram-admin-api.wwq-mixtv.workers.dev}
ADS_API=${ADS_API:-https://cigram-ads-api.wwq-mixtv.workers.dev}
: "${ADMIN_TOKEN:?set ADMIN_TOKEN (owner)}"
H="Authorization: Bearer $ADMIN_TOKEN"
fail=0
ck() { if [ "$2" = "1" ]; then echo "PASS  $1"; else echo "FAIL  $1"; fail=1; fi; }
j() { python3 -c "import sys,json
try:
  d=json.load(sys.stdin)
except Exception: print(''); sys.exit()
print(eval(sys.argv[1], {'d': d}))" "$1"; }

echo "== chat Worker (cigram-ads-api)"
P=$(curl -s "$ADS_API/chat/presence")
ck "19.0 deployed (presence.features.permanent_history)"   "$([ "$(echo "$P" | j "d.get('features',{}).get('permanent_history')")" = "True" ] && echo 1 || echo 0)"
ck "purge is refused (409 history_is_permanent)"            "$([ "$(curl -s -o /dev/null -w '%{http_code}' -H "$H" -X POST -d '{"thread":"x"}' "$ADS_API/admin/chat/purge")" = "409" ] && echo 1 || echo 0)"
ck "delete-message is refused (409)"                         "$([ "$(curl -s -o /dev/null -w '%{http_code}' -H "$H" -X POST -d '{"thread":"x","seq":1}' "$ADS_API/admin/chat/delete-message")" = "409" ] && echo 1 || echo 0)"
T=$(curl -s -H "$H" "$ADS_API/admin/chat/threads?archived=all&limit=1")
ck "admin list answers with counts"                          "$([ -n "$(echo "$T" | j "d.get('counts')")" ] && echo 1 || echo 0)"
S=$(curl -s -H "$H" "$ADS_API/admin/chat/push-status")
echo "      push: $(echo "$S" | j "d.get('features')")  devices: $(echo "$S" | j "d.get('devices')")"
echo "      missing on the Worker: $(echo "$S" | j "d.get('missing')")"
ck "FCM configured on the Worker"                            "$([ "$(echo "$S" | j "d.get('features',{}).get('push_fcm')")" = "True" ] && echo 1 || echo 0)"
B=$(curl -s -H "$H" -X POST -d '{}' "$ADS_API/admin/chat/backup")
echo "      backup run: $B" | cut -c1-300
ck "backup run succeeded (private bucket bound)"             "$([ "$(echo "$B" | j "d.get('failed')")" = "0" ] && echo 1 || echo 0)"
V=$(curl -s -H "$H" "$ADS_API/admin/chat/backup/verify")
ck "backup verification is clean"                            "$([ "$(echo "$V" | j "d.get('ok')")" = "True" ] && echo 1 || echo 0)"
echo "== catalogue Worker (cigram-admin-api)"
M=$(curl -s "$ADMIN_API/catalog/manifest")
ck "catalogue feed deployed (/catalog/manifest)"             "$([ -n "$(echo "$M" | j "d.get('rev')")" ] && echo 1 || echo 0)"
echo "(chat auth path: Cloudflare dashboard -> cigram-ads-api -> Logs: look for {\"evt\":\"chat_auth\",\"outcome\":\"unavailable\"}; see DEPLOY.md section 9.3)"
exit $fail
