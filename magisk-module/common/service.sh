#!/system/bin/sh
# Runs once per boot, as root, after system services are ready (late_start
# service stage). Applies the keep-alive/permission commands in
# apply-keepalive.sh so RecordYou doesn't need to request root itself at
# all - a plain screen/audio recorder asking for root on every launch was
# never a good look. Output goes to a log file since nobody's watching a
# boot script directly; use the "Action" button in the Magisk app (see
# action.sh) to re-run this on demand and see the result immediately.
#
# NOTE: the privileged status this module grants only applies to the RELEASE
# build (applicationId "com.bnyro.recorder"). The debug build uses
# "com.bnyro.recorder.debug" (see applicationIdSuffix in app/build.gradle.kts)
# and does not inherit it, but the appops-based commands in apply-keepalive.sh
# still cover that package whenever it is installed.

MODDIR=${0%/*}

# Give the package manager a moment to settle before touching app-ops/idle
# whitelists, in case this module's own priv-app entry is still registering.
sleep 15

{
    echo "===== $(date) ====="
    sh "$MODDIR/apply-keepalive.sh"
} >> /data/local/tmp/recordyou-keepalive.log 2>&1
