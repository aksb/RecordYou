#!/system/bin/sh
# The actual keep-alive commands, shared by common/service.sh (runs
# automatically at boot, output goes to a log file since nobody's watching)
# and action.sh (runs on demand from the Magisk app's Action button, output
# shown directly to whoever tapped it). Each command is followed by a
# verification check so it's obvious whether it actually took effect, not
# just whether the command itself ran without error.

PACKAGE_NAME="com.bnyro.recorder"

cmd appops set "${PACKAGE_NAME}" SYSTEM_ALERT_WINDOW allow
cmd appops get "${PACKAGE_NAME}" SYSTEM_ALERT_WINDOW | grep -q allow && echo "悬浮窗权限：已启用 ✔" || echo "悬浮窗权限：未启用 ✘"

cmd appops set "${PACKAGE_NAME}" RUN_IN_BACKGROUND allow
cmd appops get "${PACKAGE_NAME}" RUN_IN_BACKGROUND | grep -q allow && echo "后台运行权限：已启用 ✔" || echo "后台运行权限：未启用 ✘"

cmd appops set "${PACKAGE_NAME}" RUN_ANY_IN_BACKGROUND allow
cmd appops get "${PACKAGE_NAME}" RUN_ANY_IN_BACKGROUND | grep -q allow && echo "任意后台运行权限：已启用 ✔" || echo "任意后台运行权限：未启用 ✘"

dumpsys deviceidle whitelist "+${PACKAGE_NAME}"
dumpsys deviceidle whitelist | grep -q "${PACKAGE_NAME}" && echo "电池优化白名单：已启用 ✔" || echo "电池优化白名单：未启用 ✘"

am set-inactive "${PACKAGE_NAME}" false
am get-inactive "${PACKAGE_NAME}" | grep -q "Idle=false" && echo "待机分桶限制：已解除 ✔" || echo "待机分桶限制：未解除 ✘"
