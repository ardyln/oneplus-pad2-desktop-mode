// SPDX-License-Identifier: GPL-3.0-or-later
//
// Rewrites framework resource IDs that a prebuilt APK hard-codes from Google's
// build, so they point at the same resources in this ROM's framework-res.
//
//   CLASSPATH=patcher.dex app_process / Patcher <in.apk> <out.apk> <ids.txt>
//
// ids.txt has one section per known APK:
//
//   apk <sha256 of the stock APK>
//   <Google ID> <type> <name> <expected number of uses>
//
// The new ID is looked up by name on the running system. Only `const vAA, #+id`
// instructions are changed, found by decoding every method's code. The APK keeps
// its size and layout (dex files must be stored, not compressed), so its signing
// block stays as it was. Android doesn't verify APK contents for apps on system
// partitions, so the app keeps its signature.

import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.Adler32;
import java.util.zip.CRC32;

public class Patcher {
    static final class Entry {
        final int oldId;
        final String type, name;
        final int expected;
        int newId, found;

        Entry(int oldId, String type, String name, int expected) {
            this.oldId = oldId;
            this.type = type;
            this.name = name;
            this.expected = expected;
        }
    }

    static byte[] apk;

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            die("usage: Patcher <in.apk> <out.apk> <ids.txt>");
        }
        apk = readAll(args[0]);
        String sha = hex(MessageDigest.getInstance("SHA-256").digest(apk));
        Map<Integer, Entry> entries = loadTable(args[2], sha);
        if (entries == null) {
            die("no ID table for this APK (sha256 " + sha + ")");
        }

        resolve(entries);

        int eocd = lastIndexOf(new byte[] {'P', 'K', 5, 6});
        int cdSize = u32(eocd + 12), cdOff = u32(eocd + 16);
        for (int p = cdOff; p < cdOff + cdSize; ) {
            if (u32(p) != 0x02014b50) die("bad central directory");
            int method = u16(p + 10), size = u32(p + 24);
            int nlen = u16(p + 28), xlen = u16(p + 30), clen = u16(p + 32);
            int lho = u32(p + 42);
            String name = new String(apk, p + 46, nlen, StandardCharsets.UTF_8);
            if (name.matches("classes[0-9]*\\.dex")) {
                if (method != 0) die(name + " is compressed");
                int start = lho + 30 + u16(lho + 26) + u16(lho + 28);
                if (patchDex(name, start, size, entries)) {
                    CRC32 crc = new CRC32();
                    crc.update(apk, start, size);
                    put32(lho + 14, (int) crc.getValue());
                    put32(p + 16, (int) crc.getValue());
                }
            }
            p += 46 + nlen + xlen + clen;
        }

        for (Entry e : entries.values()) {
            if (e.found != e.expected) {
                die(String.format("%#010x %s/%s: found %d uses, expected %d; not patching",
                        e.oldId, e.type, e.name, e.found, e.expected));
            }
        }
        try (FileOutputStream out = new FileOutputStream(args[1])) {
            out.write(apk);
        }
        System.out.println("patched " + args[1]);
    }

    static Map<Integer, Entry> loadTable(String path, String sha) throws IOException {
        Map<Integer, Entry> map = null;
        boolean inSection = false;
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            for (String line; (line = r.readLine()) != null; ) {
                line = line.replaceAll("#.*", "").trim();
                if (line.isEmpty()) continue;
                String[] f = line.split("\\s+");
                if (f[0].equals("apk")) {
                    inSection = f[1].equalsIgnoreCase(sha);
                    if (inSection) map = new LinkedHashMap<>();
                } else if (inSection) {
                    int id = (int) Long.parseLong(f[0].substring(2), 16);
                    map.put(id, new Entry(id, f[1], f[2], Integer.parseInt(f[3])));
                }
            }
        }
        return map;
    }

    // Resources.getSystem().getIdentifier(name, type, "android"), via reflection
    // so this builds without the Android SDK.
    static void resolve(Map<Integer, Entry> entries) throws Exception {
        Class<?> res = Class.forName("android.content.res.Resources");
        Object sys = res.getMethod("getSystem").invoke(null);
        Method getId = res.getMethod("getIdentifier", String.class, String.class, String.class);
        for (Entry e : entries.values()) {
            e.newId = (Integer) getId.invoke(sys, e.name, e.type, "android");
            if (e.newId == 0) die("framework has no " + e.type + "/" + e.name);
            System.out.printf("%#010x -> %#010x  %s/%s%n", e.oldId, e.newId, e.type, e.name);
        }
    }

    // Instruction length in 16-bit code units, by opcode (Dalvik formats).
    static final int[] WIDTH = new int[256];
    static {
        java.util.Arrays.fill(WIDTH, 1);
        set(2, 0x02, 0x05, 0x08, 0x13, 0x15, 0x16, 0x19, 0x1a, 0x1c, 0x1f, 0x20, 0x22,
                0x23, 0x29, 0xfe, 0xff);
        set(3, 0x03, 0x06, 0x09, 0x14, 0x17, 0x1b, 0x24, 0x25, 0x26, 0x2a, 0x2b, 0x2c,
                0xfc, 0xfd);
        set(5, 0x18);
        set(4, 0xfa, 0xfb);
        range(2, 0x2d, 0x3d);   // cmp*, if-*
        range(2, 0x44, 0x6d);   // aget/aput, iget/iput, sget/sput
        range(3, 0x6e, 0x72);   // invoke-*
        range(3, 0x74, 0x78);   // invoke-*/range
        range(2, 0x90, 0xaf);   // binop
        range(2, 0xd0, 0xe2);   // binop/lit16, binop/lit8
    }

    static void set(int w, int... ops) {
        for (int op : ops) WIDTH[op] = w;
    }

    static void range(int w, int from, int to) {
        for (int op = from; op <= to; op++) WIDTH[op] = w;
    }

    static boolean patchDex(String name, int base, int size, Map<Integer, Entry> entries)
            throws Exception {
        if (apk[base] != 'd' || apk[base + 1] != 'e' || apk[base + 2] != 'x') {
            die(name + ": not a dex file");
        }
        boolean changed = false;
        Set<Integer> seen = new HashSet<>();
        int classDefs = u32(base + 0x60), classDefsOff = u32(base + 0x64);
        for (int c = 0; c < classDefs; c++) {
            int dataOff = u32(base + classDefsOff + c * 32 + 24);
            if (dataOff == 0) continue;
            int[] pos = {base + dataOff};
            int sFields = uleb(pos), iFields = uleb(pos);
            int dMethods = uleb(pos), vMethods = uleb(pos);
            for (int i = 0; i < 2 * (sFields + iFields); i++) uleb(pos);
            for (int i = 0; i < dMethods + vMethods; i++) {
                uleb(pos);
                uleb(pos);
                int codeOff = uleb(pos);
                if (codeOff != 0 && seen.add(codeOff)) {
                    changed |= patchCode(name, base + codeOff, entries);
                }
            }
        }
        if (changed) {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(apk, base + 32, size - 32);
            System.arraycopy(sha1.digest(), 0, apk, base + 12, 20);
            Adler32 adler = new Adler32();
            adler.update(apk, base + 12, size - 12);
            put32(base + 8, (int) adler.getValue());
        }
        return changed;
    }

    static boolean patchCode(String name, int code, Map<Integer, Entry> entries) {
        int insns = code + 16, units = u32(code + 12);
        boolean changed = false;
        int pc = 0;
        while (pc < units) {
            int at = insns + pc * 2;
            int unit = u16(at), op = unit & 0xff;
            int len;
            if (op == 0x00 && unit == 0x0100) {          // packed-switch payload
                len = u16(at + 2) * 2 + 4;
            } else if (op == 0x00 && unit == 0x0200) {   // sparse-switch payload
                len = u16(at + 2) * 4 + 2;
            } else if (op == 0x00 && unit == 0x0300) {   // fill-array-data payload
                long bytes = (long) u16(at + 2) * (u32(at + 4) & 0xffffffffL);
                len = (int) ((bytes + 1) / 2 + 4);
            } else {
                len = WIDTH[op];
                if (op == 0x14) {                        // const vAA, #+BBBBBBBB
                    Entry e = entries.get(u32(at + 2));
                    if (e != null) {
                        put32(at + 2, e.newId);
                        e.found++;
                        changed = true;
                    }
                }
            }
            pc += len;
        }
        if (pc != units) die(name + ": instruction stream overran a method");
        return changed;
    }

    static int uleb(int[] pos) {
        int result = 0, shift = 0, b;
        do {
            b = apk[pos[0]++] & 0xff;
            result |= (b & 0x7f) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return result;
    }

    static int u16(int at) {
        return (apk[at] & 0xff) | (apk[at + 1] & 0xff) << 8;
    }

    static int u32(int at) {
        return u16(at) | u16(at + 2) << 16;
    }

    static void put32(int at, int v) {
        for (int i = 0; i < 4; i++) apk[at + i] = (byte) (v >>> (8 * i));
    }

    static int lastIndexOf(byte[] sig) {
        outer:
        for (int i = apk.length - sig.length; i >= 0; i--) {
            for (int j = 0; j < sig.length; j++) {
                if (apk[i + j] != sig[j]) continue outer;
            }
            return i;
        }
        die("not a zip file");
        return -1;
    }

    static byte[] readAll(String path) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(path, "r")) {
            byte[] b = new byte[(int) f.length()];
            f.readFully(b);
            return b;
        }
    }

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }

    static void die(String msg) {
        System.err.println("Patcher: " + msg);
        System.exit(1);
    }
}
