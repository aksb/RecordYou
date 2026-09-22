#!/system/bin/sh
# Runs once per boot, as root, after system services are ready (late_start
# service stage). Applies the keep-alive/permission commands in
# apply-keepalive.sh so RecordYou doesn't need to request root itself at
# all - a plain screen/audio recorder asking for root on every launch was
# never a good look. Output goes to a log file since nobody's watching a
# boot script directly; use the "Action" button in the Magisk app (see
# action.sh) to re-run this on demand and see the result immediately.
#
# NOTE: this only affects the RELEASE build (applicationId "com.bnyro.recorder").
# The debug build uses "com.bnyro.recorder.debug" (see applicationIdSuffix in
# app/build.gradle.kts) and is a different package - it does not inherit the
# privileged status this module grants, and isn't covered by these commands.

MODDIR=${0%/*}

# Give the package manager a moment to settle before touching app-ops/idle
# whitelists, in case this module's own priv-app entry is still registering.
sleep 15

{
    echo "===== $(date) ====="
    sh "$MODDIR/apply-keepalive.sh"
} >> /data/local/tmp/recordyou-keepalive.log 2>&1
