##########################################################################################
#
# Magisk Module Installer Script
# RecordYou 保活模块 - 基于 you-apps/RecordYou-Magisk-Module 的标准 Magisk 模块模板
#
##########################################################################################

##########################################################################################
# Config Flags
##########################################################################################

# Set to true if you do *NOT* want Magisk to mount any files for you.
SKIPMOUNT=false

# We don't ship any system.prop
PROPFILE=false

# post-fs-data runs before most system services are up - too early for the
# `dumpsys`/`cmd appops` whitelist commands we need, so we don't use it.
POSTFSDATA=false

# late_start service runs once system services are ready - this is where
# common/service.sh applies the background-kill whitelist commands.
LATESTARTSERVICE=true

##########################################################################################
# Replace list
##########################################################################################

REPLACE="
"

##########################################################################################
#
# Function Callbacks
#
##########################################################################################

print_modname() {
  ui_print "*********************************************************	"
  ui_print "     RecordYou 保活模块 (RecordYou keep-alive module)		"
  ui_print "     ------------------Debug info--------------------	"
  ui_print "     Android API    : $API					"
  ui_print "     Magisk version : $MAGISK_VER				"
  ui_print "     Arch           : $ARCH					"
  ui_print "     Is magisk mode : $BOOTMODE				"
  ui_print "     ------------------------------------------------	"
  ui_print "*********************************************************	"
}

on_install() {
  ui_print "- Extracting module files"
  # Everything except META-INF (only needed by the flashing process itself,
  # not at runtime) gets extracted as-is.
  unzip -o "$ZIPFILE" -x 'META-INF/*' -d $MODPATH >&2

  # Magisk expects service.sh/post-fs-data.sh/etc. directly in $MODPATH -
  # "common/" is just our own repo convention for keeping the non-system
  # -partition scripts organized in source control, so flatten it out here.
  if [ -d "$MODPATH/common" ]; then
    mv "$MODPATH"/common/* "$MODPATH"/ 2>/dev/null
    rm -rf "$MODPATH/common"
  fi

  # action.sh needs to be directly executable for the Magisk app's "Action"
  # button to pick it up.
  [ -f "$MODPATH/action.sh" ] && chmod 0755 "$MODPATH/action.sh"
}

set_permissions() {
  # The following is the default rule, DO NOT remove
  set_perm_recursive $MODPATH 0 0 0755 0644
}
