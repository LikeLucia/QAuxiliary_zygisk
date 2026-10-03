#!/system/bin/sh

# Remove the payload copies cached inside each host's data directory.
for user_dir in /data/user/*/com.tencent.mobileqq/files/.qauxv \
                /data/user/*/com.tencent.mobileqqi/files/.qauxv \
                /data/user/*/com.tencent.qqlite/files/.qauxv \
                /data/user/*/com.tencent.minihd.qq/files/.qauxv \
                /data/user/*/com.tencent.tim/files/.qauxv
do
  rm -rf "$user_dir" 2>/dev/null
done

rm -rf /data/adb/qauxv 2>/dev/null

exit 0
