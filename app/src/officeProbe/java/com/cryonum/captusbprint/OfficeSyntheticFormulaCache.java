package com.cryonum.captusbprint;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** CI-only mutation of TWO pinned synthetic fixtures, never a parser/editor for user workbooks. */
final class OfficeSyntheticFormulaCache {
    private OfficeSyntheticFormulaCache() {}
    static byte[] stale(String kind, byte[] original) throws IOException, NoSuchAlgorithmException {
        String expected = kind.equals("xls")
            ? "2fee00b4ad4b868e9657d767657ca16b41a26ea4488e2a9ca3f39d778bf97fd6"
            : "92ece2258e0987ccae72b3387de7a6b37bed0c191babf33daed4320efd713aa7";
        StringBuilder sha = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(original)) {
            sha.append(Character.forDigit((value & 255) >> 4,16));
            sha.append(Character.forDigit(value & 15,16));
        }
        if (!sha.toString().equals(expected)) throw new IOException("SYNTHETIC_FIXTURE_HASH_CHANGED");
        if (kind.equals("xls")) {
            byte[] copy = original.clone();
            ByteBuffer bytes = ByteBuffer.wrap(copy).order(ByteOrder.LITTLE_ENDIAN);
            // Absolute BIFF FORMULA cached-result offsets in ONLY the SHA-pinned workbook.xls.
            if (bytes.getDouble(8318) != 50 || bytes.getDouble(8461) != 80)
                throw new IOException("SYNTHETIC_CACHE_POSITION_CHANGED");
            bytes.putDouble(8318,-12345); bytes.putDouble(8461,-12346);
            return copy;
        }
        if (!kind.equals("xlsx")) throw new IOException("SYNTHETIC_KIND_REQUIRED");
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        boolean changed = false;
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(original));
             ZipOutputStream output = new ZipOutputStream(result)) {
            ZipEntry entry;
            byte[] block = new byte[8192];
            while ((entry = input.getNextEntry()) != null) {
                ByteArrayOutputStream content = new ByteArrayOutputStream();
                int count;
                while ((count = input.read(block)) != -1) {
                    if (content.size()+count > 128*1024) throw new IOException("SYNTHETIC_PART_LIMIT");
                    content.write(block,0,count);
                }
                byte[] bytes = content.toByteArray();
                if (entry.getName().equals("xl/worksheets/sheet1.xml")) {
                    String xml = new String(bytes,StandardCharsets.UTF_8);
                    String a = "<f>B4*C4</f><v>50</v>", b = "<f>SUM(D4:D5)</f><v>80</v>";
                    if (xml.indexOf(a)<0 || xml.indexOf(a)!=xml.lastIndexOf(a)
                        || xml.indexOf(b)<0 || xml.indexOf(b)!=xml.lastIndexOf(b))
                        throw new IOException("SYNTHETIC_CACHE_XML_CHANGED");
                    bytes = xml.replace(a,"<f>B4*C4</f><v>-12345</v>")
                        .replace(b,"<f>SUM(D4:D5)</f><v>-12346</v>").getBytes(StandardCharsets.UTF_8);
                    changed = true;
                }
                ZipEntry target = new ZipEntry(entry.getName()); target.setTime(315532800000L);
                output.putNextEntry(target); output.write(bytes); output.closeEntry();
            }
        }
        if (!changed) throw new IOException("SYNTHETIC_WORKSHEET_MISSING");
        return result.toByteArray();
    }
}
