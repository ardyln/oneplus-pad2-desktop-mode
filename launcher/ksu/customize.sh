# KernelSU/Magisk installer hook.
sh "$MODPATH/patch.sh" || abort "! Not installed."
set_perm "$MODPATH/patch.sh" 0 0 0755
set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755
