// SPDX-License-Identifier: GPL-2.0
/*
 * Runtime test for the quad-pipe zpos limit in msm_drm (sm8650).
 *
 * LineageOS display-drivers: _sde_crtc_check_zpos() rejects a blend stage
 * with more than 2 planes ("zpos_cnt == 2"). The OnePlus Pad 2 OOS16 source
 * allows 2*((num_mixers+1)/2), i.e. 4 on the Pad 2's quad-pipe topology.
 *
 * In the tablet's msm_drm.ko (scmversion gec04e4f8d5c9) that check is
 * inlined into sde_crtc_atomic_check as "cmp w23, #2" at +0xbc0.
 * This module puts a kprobe on that instruction and emulates "cmp w23, #4"
 * instead. Nothing is written to flash; unloading or rebooting restores the
 * original behaviour.
 */
#include <linux/kprobes.h>
#include <linux/module.h>

#define CMP_W23_2	0x71000aff	/* subs wzr, w23, #2 */
#define ZPOS_LIMIT	4

static unsigned long hits;

static int zpos4_pre(struct kprobe *p, struct pt_regs *regs)
{
	u32 a = (u32)regs->regs[23];
	u32 res = a - ZPOS_LIMIT;
	u64 nzcv = 0;

	if (res & BIT(31))
		nzcv |= PSR_N_BIT;
	if (!res)
		nzcv |= PSR_Z_BIT;
	if (a >= ZPOS_LIMIT)
		nzcv |= PSR_C_BIT;
	if (((a ^ ZPOS_LIMIT) & (a ^ res)) & BIT(31))
		nzcv |= PSR_V_BIT;

	regs->pstate = (regs->pstate & ~(u64)(PSR_N_BIT | PSR_Z_BIT |
					      PSR_C_BIT | PSR_V_BIT)) | nzcv;
	instruction_pointer_set(regs, instruction_pointer(regs) + 4);
	if (a == 2)
		hits++;
	/* non-zero: we emulated the instruction, skip single-stepping */
	return 1;
}

static struct kprobe kp = {
	.symbol_name	= "sde_crtc_atomic_check",
	.offset		= 0xbc0,
	.pre_handler	= zpos4_pre,
	/* registered disarmed; armed only after the opcode check below */
	.flags		= KPROBE_FLAG_DISABLED,
};

static int __init zpos4_init(void)
{
	int ret = register_kprobe(&kp);

	if (ret) {
		pr_err("zpos4: register_kprobe failed: %d\n", ret);
		return ret;
	}
	/* kp.opcode holds the original instruction at the probe address */
	if (le32_to_cpu(kp.opcode) != CMP_W23_2) {
		pr_err("zpos4: unexpected instruction 0x%08x, refusing\n",
		       le32_to_cpu(kp.opcode));
		unregister_kprobe(&kp);
		return -ENOEXEC;
	}
	ret = enable_kprobe(&kp);
	if (ret) {
		unregister_kprobe(&kp);
		return ret;
	}
	pr_info("zpos4: armed at %px, stage limit %d\n", kp.addr, ZPOS_LIMIT);
	return 0;
}

static void __exit zpos4_exit(void)
{
	unregister_kprobe(&kp);
	pr_info("zpos4: removed, %lu frames had 3+ planes on one stage\n", hits);
}

module_init(zpos4_init);
module_exit(zpos4_exit);
MODULE_LICENSE("GPL");
MODULE_DESCRIPTION("Test: allow 4 planes per blend stage in msm_drm zpos check");
