#!/usr/bin/env bash
# receive-only: run after every rebase onto upstream. Fails if new code can send or call.
set -u
cd "$(dirname "$0")/.."
fail=0

# Files allowed to contain the dangerous calls (each one is guarded by ReceiveOnlyPolicy).
allowed='src/(com/android/messaging/(sms/SmsSender|sms/MmsSender|ui/UIIntentsImpl|receiver/SendStatusReceiver)\.java|androidx/appcompat/mms/MmsManager\.java)'

hits=$(grep -rnE 'sendTextMessage|sendMultipartTextMessage|sendMultimediaMessage|downloadMultimediaMessage|ACTION_CALL\b|ACTION_DIAL|sendDataMessage|sendTextMessageWithoutPersisting' src --include=*.java --include=*.kt \
    | grep -vE "^($allowed):" || true)
if [ -n "$hits" ]; then
    echo "receive-only: unguarded send/call API usage:"
    echo "$hits"
    fail=1
fi

for f in src/com/android/messaging/sms/SmsSender.java src/com/android/messaging/sms/MmsSender.java \
         src/com/android/messaging/ui/UIIntentsImpl.java; do
    grep -q 'ReceiveOnlyPolicy' "$f" || { echo "receive-only: $f lost its guard"; fail=1; }
done

if grep -qE 'SENDING_ENABLED = true|CALLING_ENABLED = true|MMS_DOWNLOAD_ENABLED = true' \
        src/com/android/messaging/util/ReceiveOnlyPolicy.kt; then
    echo "receive-only: ReceiveOnlyPolicy has an enabled flag"; fail=1
fi

if grep -qE 'permission\.(SEND_SMS|CALL_PHONE)"' AndroidManifest.xml; then
    echo "receive-only: AndroidManifest.xml requests SEND_SMS or CALL_PHONE"; fail=1
fi

[ $fail -eq 0 ] && echo "receive-only: OK"
exit $fail
