package com.lumi.uxpatcher.firmware;

import android.content.pm.ApplicationInfo;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Finds which classes implement an interface by reading the dex directly (no class is loaded) */
public final class DexScanner {
    private DexScanner() {}

    /** Names of the classes that directly implement {@code ifaceName}. */
    public static List<String> findImplementers(ApplicationInfo ai, String ifaceName) {
        List<String> apks = new ArrayList<>();
        if (ai.sourceDir != null) apks.add(ai.sourceDir);
        if (ai.splitSourceDirs != null) for (String s : ai.splitSourceDirs) apks.add(s);
        return findImplementers(apks, ifaceName);
    }

    /** Same, for explicit APK paths. */
    public static List<String> findImplementers(List<String> apks, String ifaceName) {
        List<String> out = new ArrayList<>();
        String target = "L" + ifaceName.replace('.', '/') + ";";
        for (String apk : apks) {
            try (ZipFile zf = new ZipFile(apk)) {
                Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    String n = e.getName();
                    if (!n.startsWith("classes") || !n.endsWith(".dex") || n.contains("/")) continue;
                    try (InputStream in = zf.getInputStream(e)) {
                        scan(readAll(in, (int) e.getSize()), target, out);
                    } catch (Throwable ignored) { }
                }
            } catch (Throwable ignored) { }
        }
        return out;
    }

    private static byte[] readAll(InputStream in, int sizeHint) throws java.io.IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(Math.max(sizeHint, 1 << 16));
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private static void scan(byte[] dex, String targetDescriptor, List<String> out) {
        ByteBuffer b = ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN);
        int stringIdsSize = b.getInt(0x38), stringIdsOff = b.getInt(0x3c);
        int typeIdsSize = b.getInt(0x40), typeIdsOff = b.getInt(0x44);
        int classDefsSize = b.getInt(0x60), classDefsOff = b.getInt(0x64);

        byte[] want = targetDescriptor.getBytes(StandardCharsets.UTF_8);
        int wantString = -1;
        for (int i = 0; i < stringIdsSize; i++) {
            if (stringEquals(b, b.getInt(stringIdsOff + i * 4), want)) { wantString = i; break; }
        }
        if (wantString < 0) return;
        int wantType = -1;
        for (int i = 0; i < typeIdsSize; i++) {
            if (b.getInt(typeIdsOff + i * 4) == wantString) { wantType = i; break; }
        }
        if (wantType < 0) return;

        for (int i = 0; i < classDefsSize; i++) {
            int def = classDefsOff + i * 32;
            int interfacesOff = b.getInt(def + 12);
            if (interfacesOff == 0) continue;
            int count = b.getInt(interfacesOff);
            for (int k = 0; k < count; k++) {
                if ((b.getShort(interfacesOff + 4 + k * 2) & 0xffff) == wantType) {
                    int classType = b.getInt(def);
                    String d = readString(b, b.getInt(stringIdsOff + b.getInt(typeIdsOff + classType * 4) * 4));
                    if (d.length() > 2 && d.charAt(0) == 'L' && d.endsWith(";")) {
                        out.add(d.substring(1, d.length() - 1).replace('/', '.'));
                    }
                    break;
                }
            }
        }
    }

    /** Skips the uleb128 utf16 length, then compares the NUL-terminated MUTF-8 bytes. */
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
