#!/system/bin/sh
# Magisk auto-detects this file and adds an "Action" button to this module's
# card in the Magisk app - tapping it runs this script and shows the output
# directly, no reboot needed. (After first flashing the module, a reboot is
# still required once before this button appears - that part is Magisk's own
# behavior, not something this script controls.)
#
# Re-running this by hand is mainly useful if you've manually undone one of
# the keep-alive settings (e.g. removed RecordYou from the battery whitelist
# yourself) and don't want to wait for the next reboot for service.sh to
# re-apply it.

MODDIR=${0%/*}
sh "$MODDIR/apply-keepalive.sh"
