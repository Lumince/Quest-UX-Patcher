package com.lumi.uxpatcher.firmware;

import android.content.pm.ApplicationInfo;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Finds classes whose code uses a given string constant, reading the dex directly (no class is loaded) */
public final class DexStringRefs {
    private DexStringRefs() {}

    /** Names of the classes in this app's APKs whose code references {@code needle}. */
    public static List<String> classesReferencing(ApplicationInfo ai, String needle) {
        List<String> apks = new ArrayList<>();
        if (ai.sourceDir != null) apks.add(ai.sourceDir);
        if (ai.splitSourceDirs != null) for (String s : ai.splitSourceDirs) apks.add(s);
        return classesReferencing(apks, needle);
    }

    /** Same, for explicit APK paths. */
    public static List<String> classesReferencing(List<String> apks, String needle) {
        Set<String> out = new LinkedHashSet<>();
        for (String apk : apks) {
            try (ZipFile zf = new ZipFile(apk)) {
                Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    String n = e.getName();
                    if (!n.startsWith("classes") || !n.endsWith(".dex") || n.contains("/")) continue;
                    try (InputStream in = zf.getInputStream(e)) {
                        scan(readAll(in, (int) e.getSize()), needle, out);
                    } catch (Throwable ignored) { }
                }
            } catch (Throwable ignored) { }
        }
        return new ArrayList<>(out);
    }

    private static byte[] readAll(InputStream in, int sizeHint) throws java.io.IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(Math.max(sizeHint, 1 << 16));
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private static void scan(byte[] dex, String needle, Set<String> out) {
        ByteBuffer b = ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN);
        int stringIdsSize = b.getInt(0x38), stringIdsOff = b.getInt(0x3c);
        int typeIdsOff = b.getInt(0x44);
        int classDefsSize = b.getInt(0x60), classDefsOff = b.getInt(0x64);

        byte[] want = needle.getBytes(StandardCharsets.UTF_8);
        int wantIdx = -1;
        for (int i = 0; i < stringIdsSize; i++) {
            if (stringEquals(b, b.getInt(stringIdsOff + i * 4), want)) { wantIdx = i; break; }
        }
        if (wantIdx < 0) return;

        for (int c = 0; c < classDefsSize; c++) {
            int def = classDefsOff + c * 32;
            int classDataOff = b.getInt(def + 24);
            if (classDataOff == 0) continue;
            int[] pos = {classDataOff};
            int staticFields = uleb(b, pos), instanceFields = uleb(b, pos);
            int direct = uleb(b, pos), virtual = uleb(b, pos);
            for (int i = 0; i < staticFields + instanceFields; i++) { uleb(b, pos); uleb(b, pos); }
            boolean hit = false;
            for (int pass = 0; pass < 2 && !hit; pass++) {
                int count = pass == 0 ? direct : virtual;
                for (int m = 0; m < count && !hit; m++) {
                    uleb(b, pos);                 // method_idx_diff
                    uleb(b, pos);                 // access flags
                    int codeOff = uleb(b, pos);
                    if (codeOff != 0 && refs(b, codeOff, wantIdx)) hit = true;
                }
                if (pass == 0 && !hit) continue;
            }
            if (hit) {
                int classType = b.getInt(def);
                String d = readString(b, b.getInt(stringIdsOff + b.getInt(typeIdsOff + classType * 4) * 4));
                if (d.length() > 2 && d.charAt(0) == 'L' && d.endsWith(";")) {
                    out.add(d.substring(1, d.length() - 1).replace('/', '.'));
                }
            }
        }
    }

    /** Does this code item have a const-string or const-string/jumbo of string index {@code idx}? */
    private static boolean refs(ByteBuffer b, int codeOff, int idx) {
        int insnsSize = b.getInt(codeOff + 12);
        int start = codeOff + 16;
        int end = start + insnsSize * 2;
        for (int p = start; p + 3 < end; p += 2) {
            int op = b.get(p) & 0xff;
            if (op == 0x1a) {
                if ((b.getShort(p + 2) & 0xffff) == idx) return true;
            } else if (op == 0x1b && p + 5 < end) {
                if (b.getInt(p + 2) == idx) return true;
            }
        }
        return false;
    }

    private static int uleb(ByteBuffer b, int[] pos) {
        int result = 0, shift = 0, v;
        do {
            v = b.get(pos[0]++) & 0xff;
            result |= (v & 0x7f) << shift;
            shift += 7;
        } while ((v & 0x80) != 0);
        return result;
    }

    private static boolean stringEquals(ByteBuffer b, int off, byte[] want) {
        while ((b.get(off++) & 0x80) != 0) { /* skip uleb128 */ }
        for (byte w : want) if (b.get(off++) != w) return false;
        return b.get(off) == 0;
    }

    private static String readString(ByteBuffer b, int off) {
        while ((b.get(off++) & 0x80) != 0) { /* skip uleb128 */ }
        int end = off;
        while (b.get(end) != 0) end++;
        byte[] bytes = new byte[end - off];
        for (int i = 0; i < bytes.length; i++) bytes[i] = b.get(off + i);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
