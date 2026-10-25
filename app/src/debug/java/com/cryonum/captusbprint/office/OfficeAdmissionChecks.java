package com.cryonum.captusbprint.office;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Same assertions on host and Android; no Office layout is implemented here. */
public final class OfficeAdmissionChecks {
    private static final String CT="http://schemas.openxmlformats.org/package/2006/content-types";
    private static final String REL="http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String MAIN_REL="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument";
    private static final String WORD="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml";
    private static final String DOC="<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body/></w:document>";
    private final File file;
    private int checks;
    private OfficeAdmissionChecks(File directory) { file=new File(directory,"synthetic.bin"); }
    public static void main(String[] args) throws Exception {
        File temp=Files.createTempDirectory("capt-admission-").toFile();
        try { run(new File(args[0]),temp); } finally { if (!temp.delete()) throw new IOException("CLEANUP"); }
    }
    public static int run(File fixtures,File directory) throws Exception {
        OfficeAdmissionChecks suite=new OfficeAdmissionChecks(directory);
        try { suite.check(fixtures); return suite.checks; }
        finally { if (suite.file.exists() && !suite.file.delete()) throw new IOException("CLEANUP"); }
    }
    private void check(File fixtures) throws Exception {
        for (String name : new String[]{"word.docx","word.doc","workbook.xls","workbook.xlsx"}) {
            OfficeAdmission.Kind expected=OfficeAdmission.Kind.valueOf(name.substring(name.lastIndexOf('.')+1).toUpperCase(Locale.ROOT));
            same(OfficeAdmission.inspect(new File(fixtures,name),()->false)==expected);
            // The extension and the MIME do not participate in detection.
            accept(Files.readAllBytes(new File(fixtures,name).toPath()),expected);
        }
        byte[] zip=zip(packageParts());
        accept(zip,OfficeAdmission.Kind.DOCX);
        reject(zip,OfficeAdmission.Reason.CANCELLED,OfficeAdmission.DEFAULT,()->true);
        AtomicInteger polls=new AtomicInteger();
        reject(zip,OfficeAdmission.Reason.CANCELLED,OfficeAdmission.DEFAULT,()->polls.incrementAndGet()>8);
        Thread.currentThread().interrupt();
        try { reject(zip,OfficeAdmission.Reason.CANCELLED); } finally { Thread.interrupted(); }
        reject(new byte[3],OfficeAdmission.Reason.CORRUPT);
        reject("Not an Office container".getBytes(StandardCharsets.UTF_8),OfficeAdmission.Reason.UNSUPPORTED);
        reject(zip,OfficeAdmission.Reason.LIMIT,new OfficeAdmission.Limits(8,1<<20,1<<20,100,200),()->false);
        reject(zip,OfficeAdmission.Reason.LIMIT,new OfficeAdmission.Limits(1<<20,50,1<<20,100,200),()->false);
        reject(zip,OfficeAdmission.Reason.LIMIT,new OfficeAdmission.Limits(1<<20,1<<20,50,100,200),()->false);
        reject(zip,OfficeAdmission.Reason.LIMIT,new OfficeAdmission.Limits(1<<20,1<<20,1<<20,2,200),()->false);
        Map<String,byte[]> parts=packageParts(); parts.put("large.bin",new byte[100000]);
        reject(zip(parts),OfficeAdmission.Reason.LIMIT); // Compression ratio, before allocation/inflation.
        parts=packageParts(); parts.put("../outside.xml",new byte[0]); reject(zip(parts),OfficeAdmission.Reason.CORRUPT);
        parts=packageParts(); parts.put("word%2fdocument.xml",new byte[0]); reject(zip(parts),OfficeAdmission.Reason.UNSUPPORTED);
        parts=packageParts(); parts.put("WORD/document.xml",bytes(DOC)); reject(zip(parts),OfficeAdmission.Reason.CORRUPT);
        parts=packageParts(); parts.remove("_rels/.rels"); reject(zip(parts),OfficeAdmission.Reason.CORRUPT);
        parts=packageParts(); parts.put("word/vbaProject.bin",new byte[0]); reject(zip(parts),OfficeAdmission.Reason.UNSUPPORTED);
        parts=packageParts(); parts.put("[Content_Types].xml",bytes(types(WORD.replace("openxmlformats-officedocument.wordprocessingml.document","ms-word.document.macroEnabled"))));
        reject(zip(parts),OfficeAdmission.Reason.UNSUPPORTED);
        parts=packageParts(); parts.put("_rels/.rels",bytes(rels("https://example.invalid/doc"," TargetMode=\"External\"")));
        reject(zip(parts),OfficeAdmission.Reason.UNSUPPORTED);
        parts=packageParts(); parts.put("_rels/.rels",bytes(rels("file:///data/private","")));
        reject(zip(parts),OfficeAdmission.Reason.UNSUPPORTED);
        parts=packageParts(); parts.put("word/document.xml",bytes("<wrong/>")); reject(zip(parts),OfficeAdmission.Reason.CORRUPT);
        parts=packageParts(); parts.put("word/document.xml",bytes("<broken>")); reject(zip(parts),OfficeAdmission.Reason.CORRUPT);
        parts=packageParts(); parts.put("_rels/.rels",bytes("<!DOCTYPE Relationships [<!ENTITY secret SYSTEM 'file:///PRIVATE_SENTINEL'>]>"+rels("word/document.xml","")));
        reject(zip(parts),OfficeAdmission.Reason.UNSUPPORTED);
        parts=packageParts(); parts.put("word/document.xml",bytes("<!DOCTYPE document [<!ENTITY x 'EXPANSION'>]>"+DOC));
        reject(zip(parts),OfficeAdmission.Reason.UNSUPPORTED);
        parts=packageParts(); parts.put("word/document.xml",bytes(DOC.replace("<w:body/>","<w:body>"+repeat("<w:p>",130)+repeat("</w:p>",130)+"</w:body>")));
        reject(zip(parts),OfficeAdmission.Reason.LIMIT);
        parts=packageParts(); parts.put("word/document.xml",bytes(DOC.replace("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "http://purl.oclc.org/ooxml/wordprocessingml/main")));
        reject(zip(parts),OfficeAdmission.Reason.UNSUPPORTED);
        parts=packageParts(); parts.put("_rels/.rels",bytes(rels("../../outside.xml",""))); reject(zip(parts),OfficeAdmission.Reason.CORRUPT);
        byte[] mutated=zip.clone(); mutated[6]|=1; // Local/central flag disagreement.
        reject(mutated,OfficeAdmission.Reason.CORRUPT);
        mutated=zip.clone(); int central=index(mutated,new byte[]{0x50,0x4b,1,2}); mutated[central+8]|=1;
        reject(mutated,OfficeAdmission.Reason.PASSWORD);
        mutated=zip.clone(); put32(mutated,central+38,0xa0000000); reject(mutated,OfficeAdmission.Reason.UNSUPPORTED);
        mutated=zip.clone(); mutated[central+16]^=1; reject(mutated,OfficeAdmission.Reason.CORRUPT);
        mutated=zip.clone(); mutated[central+46]^=1; reject(mutated,OfficeAdmission.Reason.CORRUPT);
        mutated=Arrays.copyOf(zip,zip.length-1); reject(mutated,OfficeAdmission.Reason.CORRUPT);
        mutated=Arrays.copyOf(zip,zip.length+1); reject(mutated,OfficeAdmission.Reason.CORRUPT);
        // A central record cannot alias the same local interval under another name.
        mutated=zip.clone(); int second=central+46+u16(zip,central+28)+u16(zip,central+30)+u16(zip,central+32);
        put32(mutated,second+42,0); reject(mutated,OfficeAdmission.Reason.CORRUPT);
        for (int major : new int[]{3,4}) {
            byte[] compound=compound(major,word(),false);
            accept(compound,OfficeAdmission.Kind.DOC); // mini-FAT and 512/4096-byte sectors.
            reject(compound,OfficeAdmission.Reason.CANCELLED,OfficeAdmission.DEFAULT,()->true);
            mutated=compound.clone(); int sector=major==3?512:4096;
            put32(mutated,sector+4,1); reject(mutated,OfficeAdmission.Reason.CORRUPT); // directory cycle
            mutated=compound.clone(); put32(mutated,2*sector+128+72,1); reject(mutated,OfficeAdmission.Reason.CORRUPT); // sibling cycle
            mutated=compound.clone(); put32(mutated,3*sector,0); reject(mutated,OfficeAdmission.Reason.CORRUPT); // mini chain cycle
            mutated=compound.clone(); put32(mutated,2*sector+256+116,0); reject(mutated,OfficeAdmission.Reason.CORRUPT); // stream overlap
            mutated=compound.clone(); put32(mutated,2*sector+128+124,1); reject(mutated,OfficeAdmission.Reason.LIMIT);
            mutated=Arrays.copyOf(compound,compound.length-1); reject(mutated,OfficeAdmission.Reason.CORRUPT);
        }
        Map<String,byte[]> streams=word(); streams.get("WordDocument")[11]|=1;
        reject(compound(3,streams,false),OfficeAdmission.Reason.PASSWORD);
        streams=word(); streams.get("WordDocument")[10]|=1; reject(compound(3,streams,false),OfficeAdmission.Reason.UNSUPPORTED);
        streams=word(); streams.get("WordDocument")[2]=0x65;
        reject(compound(3,streams,false),OfficeAdmission.Reason.UNSUPPORTED);
        streams=word(); streams.remove("0Table"); reject(compound(3,streams,false),OfficeAdmission.Reason.CORRUPT);
        streams=word(); streams.put("VBA",new byte[0]); reject(compound(3,streams,false),OfficeAdmission.Reason.UNSUPPORTED);
        streams=new LinkedHashMap<>(); streams.put("EncryptionInfo",new byte[8]); streams.put("EncryptedPackage",new byte[32]);
        reject(compound(3,streams,false),OfficeAdmission.Reason.PASSWORD);
        streams=new LinkedHashMap<>(); streams.put("Workbook",biff(false)); accept(compound(3,streams,false),OfficeAdmission.Kind.XLS);
        streams.put("Workbook",biff(true)); reject(compound(3,streams,false),OfficeAdmission.Reason.PASSWORD);
        streams.put("Workbook",new byte[]{9,8,16,0,0,5,5,0}); reject(compound(3,streams,false),OfficeAdmission.Reason.UNSUPPORTED);
        streams=word(); streams.put("Workbook",biff(false)); reject(compound(3,streams,false),OfficeAdmission.Reason.UNSUPPORTED);
        byte[] extended=extendedDifat();
        accept(extended,OfficeAdmission.Kind.DOC);
        put32(extended,68,1); reject(extended,OfficeAdmission.Reason.CORRUPT);
        // Regular FAT streams, independent of the mini-stream path.
        accept(compound(3,word(),true),OfficeAdmission.Kind.DOC);
        System.out.println("CAPT_OFFICE_ADMISSION_PASSED ("+checks+" checks; container admission, no rendering/USB)");
    }
    private void same(boolean value) { if (!value) throw new AssertionError("CHECK_"+checks); checks++; }
    private void accept(byte[] bytes,OfficeAdmission.Kind kind) throws Exception {
        Files.write(file.toPath(),bytes); same(OfficeAdmission.inspect(file,()->false)==kind);
    }
    private void reject(byte[] bytes,OfficeAdmission.Reason reason) throws Exception { reject(bytes,reason,OfficeAdmission.DEFAULT,()->false); }
    private void reject(byte[] bytes,OfficeAdmission.Reason reason,OfficeAdmission.Limits limits,BooleanSupplier cancelled) throws Exception {
        Files.write(file.toPath(),bytes);
        try { OfficeAdmission.inspect(file,limits,cancelled); throw new AssertionError("ACCEPTED_"+checks); }
        catch (OfficeAdmission.Failure error) {
            if (error.reason!=reason) throw new AssertionError("CHECK_"+checks+":"+reason+":"+error.reason);
            same(error.getMessage().equals(reason.name()) && error.getCause()==null && !error.toString().contains("PRIVATE_SENTINEL"));
        }
    }
    private static String repeat(String s,int n) { StringBuilder b=new StringBuilder(); for (int i=0;i<n;i++) b.append(s); return b.toString(); }
    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    private static String types(String type) { return "<Types xmlns=\""+CT+"\"><Override PartName=\"/word/document.xml\" ContentType=\""+type+"\"/></Types>"; }
    private static String rels(String target,String mode) { return "<Relationships xmlns=\""+REL+"\"><Relationship Id=\"r1\" Type=\""+MAIN_REL+"\" Target=\""+target+"\""+mode+"/></Relationships>"; }
    private static Map<String,byte[]> packageParts() {
        Map<String,byte[]> parts=new LinkedHashMap<>(); parts.put("[Content_Types].xml",bytes(types(WORD)));
        parts.put("_rels/.rels",bytes(rels("word/document.xml",""))); parts.put("word/document.xml",bytes(DOC)); return parts;
    }
    private static byte[] zip(Map<String,byte[]> parts) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try (ZipOutputStream zip=new ZipOutputStream(out)) {
            for (Map.Entry<String,byte[]> p : parts.entrySet()) { zip.putNextEntry(new ZipEntry(p.getKey())); zip.write(p.getValue()); zip.closeEntry(); }
        }
        return out.toByteArray();
    }
    private static int index(byte[] data,byte[] match) {
        outer: for (int p=0;p<=data.length-match.length;p++) { for (int j=0;j<match.length;j++) if (data[p+j]!=match[j]) continue outer; return p; }
        throw new AssertionError("SYNTHETIC_STRUCTURE");
    }
    private static int u16(byte[] b,int p) { return (b[p]&255)|((b[p+1]&255)<<8); }
    private static void put16(byte[] b,int p,int v) { b[p]=(byte)v; b[p+1]=(byte)(v>>8); }
    private static void put32(byte[] b,int p,int v) { put16(b,p,v); put16(b,p+2,v>>16); }
    private static Map<String,byte[]> word() {
        Map<String,byte[]> streams=new LinkedHashMap<>(); byte[] fib=new byte[32]; put16(fib,0,0xa5ec); put16(fib,2,0xc1);
        streams.put("WordDocument",fib); streams.put("0Table",new byte[64]); return streams;
    }
    private static byte[] biff(boolean password) {
        byte[] b=new byte[password?30:24]; put16(b,0,0x809); put16(b,2,16); put16(b,4,0x600); put16(b,6,5);
        if (password) { put16(b,20,0x2f); put16(b,22,2); put16(b,26,0xa); } else put16(b,20,0xa); return b;
    }
    /** Tiny valid container; small streams in mini-FAT or padded regular streams. */
    private static byte[] compound(int major,Map<String,byte[]> streams,boolean regular) {
        int sector=major==3?512:4096, n=streams.size(), units=0;
        for (byte[] value : streams.values()) units+=regular?4096/sector:(value.length+63)/64;
        int payloadSectors=regular?units:(units*64+sector-1)/sector;
        int sectors=(regular?2:3)+payloadSectors;
        byte[] b=new byte[(sectors+1)*sector]; byte[] signature={(byte)0xd0,(byte)0xcf,0x11,(byte)0xe0,(byte)0xa1,(byte)0xb1,0x1a,(byte)0xe1};
        System.arraycopy(signature,0,b,0,8); put16(b,24,0x3e); put16(b,26,major); put16(b,28,0xfffe); put16(b,30,major==3?9:12); put16(b,32,6);
        put32(b,40,major==4?1:0); put32(b,44,1); put32(b,48,1); put32(b,56,4096);
        put32(b,60,regular?-2:2); put32(b,64,regular?0:1); put32(b,68,-2);
        for (int i=0;i<109;i++) put32(b,76+i*4,i==0?0:-1);
        for (int i=0;i<sector/4;i++) put32(b,sector+i*4,-1);
        put32(b,sector,-3); put32(b,sector+4,-2);
        if (!regular) {
            put32(b,sector+8,-2);
            for (int i=0;i<sector/4;i++) put32(b,3*sector+i*4,-1);
            for (int i=0;i<payloadSectors;i++) put32(b,sector+(3+i)*4,i+1==payloadSectors?-2:4+i);
        }
        entry(b,2*sector,"Root Entry",5,-1,-1,n==0?-1:1,regular?-2:3,regular?0:units*64);
        int id=1, unit=0;
        for (Map.Entry<String,byte[]> stream : streams.entrySet()) {
            byte[] value=stream.getValue(); int len=regular?4096:value.length, amount=regular?4096/sector:(len+63)/64;
            int start=amount==0?-2:regular?2+unit:unit;
            entry(b,2*sector+id*128,stream.getKey(),2,-1,id<n?id+1:-1,-1,start,len);
            if (regular) {
                for (int j=0;j<amount;j++) put32(b,sector+(2+unit+j)*4,j+1==amount?-2:3+unit+j);
                System.arraycopy(value,0,b,(3+unit)*sector,value.length);
            } else {
                for (int j=0;j<amount;j++) put32(b,3*sector+(unit+j)*4,j+1==amount?-2:unit+j+1);
                System.arraycopy(value,0,b,4*sector+unit*64,value.length);
            }
            unit+=amount; id++;
        }
        return b;
    }
    /** More than 109 FAT sectors exercises the actual DIFAT continuation, not only header entries. */
    private static byte[] extendedDifat() {
        int dataSectors=14000, fatCount=111, difat=fatCount, dir=difat+1, first=dir+1, count=first+dataSectors;
        byte[] b=new byte[(count+1)*512];
        byte[] base=compound(3,word(),true); System.arraycopy(base,0,b,0,512);
        put32(b,44,fatCount); put32(b,48,dir); put32(b,68,difat); put32(b,72,1);
        for (int i=0;i<109;i++) put32(b,76+i*4,i);
        for (int i=0;i<fatCount*128;i++) put32(b,512+i*4,-1);
        for (int i=0;i<fatCount;i++) put32(b,512+i*4,-3);
        put32(b,512+difat*4,-4); put32(b,512+dir*4,-2);
        for (int i=0;i<128;i++) put32(b,(difat+1)*512+i*4,i<2?109+i:i==127?-2:-1);
        entry(b,(dir+1)*512,"Root Entry",5,-1,-1,1,-2,0);
        entry(b,(dir+1)*512+128,"WordDocument",2,-1,2,-1,first,(dataSectors-8)*512);
        entry(b,(dir+1)*512+256,"0Table",2,-1,-1,-1,first+dataSectors-8,4096);
        for (int i=0;i<dataSectors;i++) put32(b,512+(first+i)*4,(i==dataSectors-9 || i==dataSectors-1)?-2:first+i+1);
        put16(b,(first+1)*512,0xa5ec); put16(b,(first+1)*512+2,0xc1); return b;
    }
    private static void entry(byte[] b,int p,String name,int type,int left,int right,int child,int start,int size) {
        byte[] text=name.getBytes(StandardCharsets.UTF_16LE); System.arraycopy(text,0,b,p,text.length); put16(b,p+64,text.length+2);
        b[p+66]=(byte)type; b[p+67]=1; put32(b,p+68,left); put32(b,p+72,right); put32(b,p+76,child); put32(b,p+116,start); put32(b,p+120,size);
    }
}
