#!/system/bin/sh

SKIPUNZIP=1

if [ "$BOOTMODE" != "true" ]; then
  ui_print "*********************************************************"
  ui_print "! Install from recovery is not supported"
  ui_print "! Please install from KernelSU, APatch or Magisk app"
  abort    "*********************************************************"
fi

ui_print "- Installing QAuxiliary Zygisk"

if [ "$ARCH" != "arm64" ]; then
  abort "! Unsupported platform: $ARCH (only arm64-v8a)"
fi

ui_print "- Extracting module files"
unzip -o "$ZIPFILE" 'module.prop' -d "$MODPATH" >&2
unzip -o "$ZIPFILE" 'uninstall.sh' -d "$MODPATH" >&2

mkdir -p "$MODPATH/zygisk"

ui_print "- Extracting Zygisk library"
unzip -o "$ZIPFILE" 'zygisk/arm64-v8a.so' -d "$MODPATH" >&2 ||
  abort "! Missing arm64-v8a Zygisk library"

ui_print "- Storing QAuxiliary package"
DATAPATH="/data/adb/qauxv"
mkdir -p "$DATAPATH" || abort "! Failed to create $DATAPATH"
cp "$ZIPFILE" "$DATAPATH/main.apk.tmp" || abort "! Failed to store QAuxiliary package"
mv -f "$DATAPATH/main.apk.tmp" "$DATAPATH/main.apk"
set_perm "$DATAPATH/main.apk" 0 0 0644

ui_print "- Calculating QAuxiliary package hash"
# The injector compares this fingerprint with the copy cached in each host data
# directory to decide whether the cached payload needs refreshing. Size is
# deliberately NOT compared: two versions can coincidentally have the same size.
APK_HASH="$(sha256sum "$DATAPATH/main.apk" 2>/dev/null | cut -d ' ' -f1)"
if [ -z "$APK_HASH" ]; then
  abort "! Failed to calculate QAuxiliary package hash"
fi
printf '%s' "$APK_HASH" > "$DATAPATH/main.apk.sha256"
set_perm "$DATAPATH/main.apk.sha256" 0 0 0644

# clean up leftovers from older layouts (payload/*.dex, dex.list)
rm -rf "$MODPATH/payload"

ui_print "- Setting permissions"
set_perm_recursive "$MODPATH/zygisk" 0 0 0755 0644
set_perm "$MODPATH/module.prop" 0 0 0644
set_perm "$MODPATH/uninstall.sh" 0 0 0755

ui_print "- Fixing SELinux contexts"
chcon -R u:object_r:system_file:s0 "$MODPATH" 2>/dev/null || true
chcon u:object_r:system_lib_file:s0 "$MODPATH/zygisk/arm64-v8a.so" 2>/dev/null || true

# A changed injector .so is only picked up when the zygote restarts, i.e. on reboot.
# A changed payload APK is re-copied on the next app start, no reboot needed.
NEW_SO_HASH="$(sha1sum "$MODPATH/zygisk/arm64-v8a.so" 2>/dev/null | cut -d ' ' -f1)"
OLD_SO_HASH="$(cat "$DATAPATH/so.sha1" 2>/dev/null | tr -d ' \r\n')"
if [ -n "$OLD_SO_HASH" ] && [ "$OLD_SO_HASH" = "$NEW_SO_HASH" ]; then
  ui_print "- 重启 QQ/TIM 即可生效（无需重启设备）"
else
  ui_print "! 需要重启设备后才会生效"
fi
printf '%s' "$NEW_SO_HASH" > "$DATAPATH/so.sha1"
set_perm "$DATAPATH/so.sha1" 0 0 0644

ui_print ""
ui_print "- 注入控制：在 /data/adb/qauxv 下创建 <宿主包名>.disable 可单独停用该宿主"
ui_print "  例如：touch /data/adb/qauxv/com.tencent.mobileqq.disable"
ui_print "  多用户：/data/adb/qauxv/user_<userId>/<宿主包名>.disable"
ui_print "- 兼容性模式（在 Application.onCreate 阶段启动）："
ui_print "  touch /data/adb/qauxv/compat.enable"
ui_print "! 提示: 使用 Zygisk 模式时，请勿在 LSPosed 中对 QQ/TIM 启用 QAuxiliary，否则会重复注入。"
ui_print "- QAuxiliary Zygisk installed"
