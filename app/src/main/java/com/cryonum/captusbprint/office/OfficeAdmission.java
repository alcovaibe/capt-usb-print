package com.cryonum.captusbprint.office;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.ext.DefaultHandler2;

/** Bounded container admission, not an Office renderer or a sandbox for the native importer. */
public final class OfficeAdmission {
    public enum Kind { DOCX, DOC, XLS, XLSX }
    public enum Reason { CORRUPT, UNSUPPORTED, PASSWORD, LIMIT, CANCELLED }
    public static final class Failure extends IOException {
        private static final long serialVersionUID = 1L;
        public final Reason reason;
        Failure(Reason reason) { super(reason.name()); this.reason = reason; }
    }
    public static final class Limits {
        public final long sourceBytes, expandedBytes, partBytes;
        public final int entries, ratio;
        public Limits(long sourceBytes, long expandedBytes, long partBytes, int entries, int ratio) {
            if (sourceBytes < 1 || expandedBytes < 1 || partBytes < 1 || entries < 1 || ratio < 1)
                throw new IllegalArgumentException();
            this.sourceBytes = sourceBytes; this.expandedBytes = expandedBytes;
            this.partBytes = partBytes; this.entries = entries; this.ratio = ratio;
        }
    }
    public static final Limits DEFAULT = new Limits(64L << 20, 128L << 20, 32L << 20, 4096, 200);
    private static final long XML_BYTES = 4L << 20;
    private static final int END = -2, FREE = -1;
    private static final String CT = "http://schemas.openxmlformats.org/package/2006/content-types";
    private static final String REL = "http://schemas.openxmlformats.org/package/2006/relationships";
    private static final String OFFICE_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument";
    private final Limits limits;
    private final BooleanSupplier cancelled;
    private OfficeAdmission(Limits limits, BooleanSupplier cancelled) {
        this.limits = limits; this.cancelled = cancelled;
    }
    public static Kind inspect(File file, BooleanSupplier cancelled) throws IOException {
        return inspect(file, DEFAULT, cancelled);
    }
    public static Kind inspect(File file, Limits limits, BooleanSupplier cancelled) throws IOException {
        OfficeAdmission gate = new OfficeAdmission(limits, cancelled);
        gate.tick(); // Cancel before opening a descriptor, including an already-interrupted caller.
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            gate.tick();
            if (in.length() > limits.sourceBytes) fail(Reason.LIMIT);
            if (in.length() < 8) fail(Reason.CORRUPT);
            byte[] signature = new byte[8]; in.readFully(signature);
            if (u32(signature, 0) == 0x04034b50L) return gate.opc(file, in);
            if (Arrays.equals(signature, new byte[] {(byte)0xd0,(byte)0xcf,0x11,(byte)0xe0,(byte)0xa1,(byte)0xb1,0x1a,(byte)0xe1}))
                return gate.cfb(in);
            fail(Reason.UNSUPPORTED); return null;
        } catch (Failure error) { throw error; }
        catch (IOException | RuntimeException error) { throw new Failure(Reason.CORRUPT); }
    }
    private void tick() throws Failure {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) fail(Reason.CANCELLED);
    }
    private static void fail(Reason reason) throws Failure { throw new Failure(reason); }
    private static void require(boolean value) throws Failure { if (!value) fail(Reason.CORRUPT); }
    private static int u16(byte[] b, int p) { return (b[p] & 255) | ((b[p+1] & 255) << 8); }
    private static long u32(byte[] b, int p) { return (long)u16(b,p) | ((long)u16(b,p+2) << 16); }
    private static int i32(byte[] b, int p) { return (int)u32(b,p); }
    private static long size64(byte[] b, int p) throws Failure {
        if (u32(b,p+4) != 0) fail(Reason.LIMIT);
        return u32(b,p);
    }
    private static byte[] read(RandomAccessFile in, long pos, int n) throws IOException {
        require(pos >= 0 && n >= 0 && pos <= in.length() - n);
        byte[] b = new byte[n]; in.seek(pos); in.readFully(b); return b;
    }
    private static String path(byte[] bytes) throws Failure {
        // Conservative supported subset: URI-escaped/non-ASCII names need separate equivalence tests.
        for (byte b : bytes) if (b < 33 || b > 126) fail(Reason.UNSUPPORTED);
        String name = new String(bytes, StandardCharsets.US_ASCII);
        if (name.length() > 512) fail(Reason.LIMIT);
        if (name.startsWith("/") || name.contains("\\") || name.contains("%") || name.contains(":") || name.contains("?") || name.contains("#"))
            fail(Reason.UNSUPPORTED);
        String clean = name.endsWith("/") ? name.substring(0,name.length()-1) : name;
        for (String part : clean.split("/", -1)) if (part.isEmpty() || part.equals(".") || part.equals("..")) fail(Reason.CORRUPT);
        return name;
    }
    private static final class Part {
        String name;
        long crc, compressed, size, offset;
        int method, flags;
        byte[] nameBytes;
    }
    private Kind opc(File file, RandomAccessFile in) throws IOException {
        List<Part> parts = zipDirectory(in);
        Map<String,byte[]> metadata = new HashMap<>();
        Set<String> names = new HashSet<>();
        Map<String,String> roots = new HashMap<>();
        long total = 0, metadataBytes = 0;
        try (ZipFile zip = new ZipFile(file)) {
            require(zip.size() == parts.size());
            Enumeration<? extends ZipEntry> entries = zip.entries();
            byte[] buffer = new byte[32768];
            for (Part p : parts) {
                tick(); require(entries.hasMoreElements());
                ZipEntry entry = entries.nextElement();
                require(entry.getName().equals(p.name) && entry.getSize() == p.size && entry.getCrc() == p.crc);
                names.add(p.name);
                String lower = p.name.toLowerCase(Locale.ROOT);
                if (lower.contains("vbaproject") || lower.contains("activex/") || lower.contains("embeddings/")) fail(Reason.UNSUPPORTED);
                boolean xml = lower.endsWith(".xml") || lower.endsWith(".rels") || p.name.equals("[Content_Types].xml");
                boolean keep = p.name.equals("[Content_Types].xml") || lower.endsWith(".rels");
                ByteArrayOutputStream saved = xml ? new ByteArrayOutputStream() : null;
                CRC32 crc = new CRC32(); long count = 0;
                try (InputStream stream = zip.getInputStream(entry)) {
                    int n;
                    while ((n = stream.read(buffer)) != -1) {
                        tick(); count += n; total += n;
                        if (count > limits.partBytes || total > limits.expandedBytes || (xml && count > XML_BYTES)) fail(Reason.LIMIT);
                        crc.update(buffer,0,n); if (saved != null) saved.write(buffer,0,n);
                    }
                }
                require(count == p.size && crc.getValue() == p.crc);
                if (xml) {
                    byte[] bytes = saved.toByteArray();
                    if (!keep) {
                        PackageXml checked = xml(bytes, 0);
                        roots.put(p.name, checked.rootName);
                    } else {
                        metadataBytes += count;
                        if (metadataBytes > 8L*1024*1024) fail(Reason.LIMIT);
                        metadata.put(p.name, bytes);
                    }
                }
            }
        }
        byte[] contentTypes = metadata.get("[Content_Types].xml"), relationships = metadata.get("_rels/.rels");
        require(contentTypes != null && relationships != null);
        PackageXml types = xml(contentTypes, 1);
        String main = null;
        for (Map.Entry<String,byte[]> item : metadata.entrySet()) {
            if (!item.getKey().endsWith(".rels")) continue;
            PackageXml rels = xml(item.getValue(), 2);
            for (String target : rels.targets) {
                String key = item.getKey();
                int directory = key.lastIndexOf("/_rels/");
                String base = key.equals("_rels/.rels") ? "" : directory >= 0 ? key.substring(0,directory+1) : null;
                require(base != null);
                try {
                    java.net.URI uri = new java.net.URI(target);
                    if (uri.getRawFragment()!=null || uri.getRawQuery()!=null || target.contains("%")) fail(Reason.UNSUPPORTED);
                    String resolved = new java.net.URI("/"+base).resolve(uri).normalize().getPath();
                    require(resolved != null && resolved.startsWith("/") && !resolved.startsWith("/../") && names.contains(resolved.substring(1)));
                } catch (java.net.URISyntaxException error) { fail(Reason.CORRUPT); }
            }
            if (item.getKey().equals("_rels/.rels")) main = rels.main;
        }
        require(main != null && names.contains(main));
        String type = types.types.get("/" + main);
        if (type == null) type = types.types.get("extension:" + main.substring(main.lastIndexOf('.')+1));
        if ("application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml".equals(type)) {
            if ("http://purl.oclc.org/ooxml/wordprocessingml/main|document".equals(roots.get(main))) fail(Reason.UNSUPPORTED);
            require("http://schemas.openxmlformats.org/wordprocessingml/2006/main|document".equals(roots.get(main)));
            return Kind.DOCX;
        }
        if ("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml".equals(type)) {
            if ("http://purl.oclc.org/ooxml/spreadsheetml/main|workbook".equals(roots.get(main))) fail(Reason.UNSUPPORTED);
            require("http://schemas.openxmlformats.org/spreadsheetml/2006/main|workbook".equals(roots.get(main)));
            return Kind.XLSX;
        }
        fail(Reason.UNSUPPORTED); return null;
    }
    /** Require one unambiguous non-ZIP64 ZIP view, including local headers/descriptors and CRCs. */
    private List<Part> zipDirectory(RandomAccessFile in) throws IOException {
        long length = in.length(), end = -1;
        byte[] tail = read(in, Math.max(0,length-65557), (int)Math.min(length,65557));
        for (int i = tail.length-22; i >= 0; i--) {
            if (u32(tail,i) == 0x06054b50L && i+22+u16(tail,i+20) == tail.length) {
                require(end == -1); end = length-tail.length+i;
            }
        }
        require(end >= 0);
        byte[] e = read(in,end,22);
        require(u16(e,4)==0 && u16(e,6)==0 && u16(e,8)==u16(e,10));
        int count = u16(e,10);
        if (count == 65535 || count > limits.entries) fail(Reason.LIMIT);
        long central = u32(e,16), centralSize = u32(e,12);
        require(central+centralSize == end && count > 0);
        List<Part> parts = new ArrayList<>(); Set<String> unique = new HashSet<>();
        long cursor = central, expanded = 0;
        for (int i=0;i<count;i++) {
            tick(); byte[] h = read(in,cursor,46); require(u32(h,0)==0x02014b50L);
            Part p = new Part(); p.flags=u16(h,8); p.method=u16(h,10); p.crc=u32(h,16);
            p.compressed=u32(h,20); p.size=u32(h,24); p.offset=u32(h,42);
            if ((p.flags & 1)!=0) fail(Reason.PASSWORD);
            if ((p.flags & ~0x808)!=0 || (p.method!=0 && p.method!=8) || u16(h,34)!=0) fail(Reason.UNSUPPORTED);
            if (((u32(h,38) >>> 16) & 0xf000)==0xa000) fail(Reason.UNSUPPORTED);
            int nameLength=u16(h,28), extra=u16(h,30), comment=u16(h,32);
            require(nameLength>0 && nameLength<=512);
            p.nameBytes=read(in,cursor+46,nameLength); p.name=path(p.nameBytes);
            require(unique.add(p.name.toLowerCase(Locale.ROOT)));
            if (p.size>limits.partBytes || p.size>limits.expandedBytes-expanded ||
                p.size>Math.max(1,p.compressed)*limits.ratio) fail(Reason.LIMIT);
            expanded+=p.size;
            require(!p.name.endsWith("/") || p.size==0);
            if (p.method==0) require(p.compressed==p.size);
            rejectZip64(read(in,cursor+46+nameLength,extra));
            cursor+=46L+nameLength+extra+comment; require(cursor<=end); parts.add(p);
        }
        require(cursor==end);
        // Local order may differ from central order. The occupied intervals must cover exactly the payload.
        List<Part> ordered = new ArrayList<>(parts); ordered.sort((a,b)->Long.compare(a.offset,b.offset));
        cursor=0;
        for (Part p : ordered) {
            tick(); require(p.offset==cursor);
            byte[] h=read(in,cursor,30); require(u32(h,0)==0x04034b50L && u16(h,6)==p.flags && u16(h,8)==p.method);
            int n=u16(h,26), extra=u16(h,28);
            require(n==p.nameBytes.length && Arrays.equals(read(in,cursor+30,n),p.nameBytes));
            rejectZip64(read(in,cursor+30+n,extra));
            cursor+=30L+n+extra+p.compressed; require(cursor<=central);
            if ((p.flags & 8)==0) require(u32(h,14)==p.crc && u32(h,18)==p.compressed && u32(h,22)==p.size);
            else {
                byte[] descriptor=read(in,cursor,12);
                if (u32(descriptor,0)==0x08074b50L) { cursor+=4; descriptor=read(in,cursor,12); }
                require(u32(descriptor,0)==p.crc && u32(descriptor,4)==p.compressed && u32(descriptor,8)==p.size);
                cursor+=12;
            }
        }
        require(cursor==central); return parts;
    }
    private static void rejectZip64(byte[] extra) throws Failure {
        int p=0;
        while (p<extra.length) {
            require(p+4<=extra.length); int id=u16(extra,p), n=u16(extra,p+2); p+=4;
            if (id==1) fail(Reason.UNSUPPORTED);
            require(n<=extra.length-p); p+=n;
        }
    }
    private PackageXml xml(byte[] bytes, int mode) throws Failure {
        try {
            SAXParserFactory factory=SAXParserFactory.newInstance(); factory.setNamespaceAware(true);
            XMLReader reader=factory.newSAXParser().getXMLReader();
            reader.setFeature("http://xml.org/sax/features/external-general-entities",false);
            reader.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
            PackageXml handler=new PackageXml(mode);
            reader.setContentHandler(handler); reader.setErrorHandler(handler); reader.setEntityResolver(handler);
            reader.setProperty("http://xml.org/sax/properties/lexical-handler",handler);
            reader.parse(new InputSource(new ByteArrayInputStream(bytes)));
            require(handler.root && handler.depth==0); return handler;
        } catch (Failure error) { throw error; }
        catch (Exception error) {
            if (error instanceof SAXException && ((SAXException)error).getException() instanceof Failure)
                throw (Failure)((SAXException)error).getException();
            throw new Failure(Reason.CORRUPT);
        }
    }
    private final class PackageXml extends DefaultHandler2 {
        final boolean contentTypes; final int mode;
        final Map<String,String> types=new HashMap<>();
        final Set<String> ids=new HashSet<>();
        final List<String> targets=new ArrayList<>();
        int depth, elements; boolean root; String main, rootName;
        PackageXml(int mode) { this.mode=mode; contentTypes=mode==1; }
        private void reject(Reason reason) throws SAXException { throw new SAXException(new Failure(reason)); }
        @Override public void startDTD(String name,String publicId,String systemId) throws SAXException { reject(Reason.UNSUPPORTED); }
        @Override public InputSource resolveEntity(String publicId,String systemId) throws SAXException { reject(Reason.UNSUPPORTED); return null; }
        @Override public void startElement(String uri,String local,String q,Attributes a) throws SAXException {
            try { tick(); } catch (Failure error) { throw new SAXException(error); }
            if (++elements>(mode==0?200000:20000) || ++depth>(mode==0?128:32) || a.getLength()>(mode==0?256:32)) reject(Reason.LIMIT);
            for (int i=0;i<a.getLength();i++) if (a.getValue(i).length()>8192) reject(Reason.LIMIT);
            if (depth==1) {
                root=true; rootName=uri+"|"+local;
                if (mode==0) return;
                if (!(contentTypes?CT:REL).equals(uri) || !(contentTypes?"Types":"Relationships").equals(local)) reject(Reason.CORRUPT);
                return;
            }
            if (mode==0) return;
            if (depth!=2 || !(contentTypes?CT:REL).equals(uri)) reject(Reason.UNSUPPORTED);
            if (contentTypes) {
                if (!local.equals("Override") && !local.equals("Default")) reject(Reason.CORRUPT);
                String type=a.getValue("ContentType"); if (type==null || type.length()>256) reject(Reason.CORRUPT);
                String lower=type.toLowerCase(Locale.ROOT);
                if (lower.contains("macroenabled") || lower.contains("vbaproject") || lower.contains("activex")) reject(Reason.UNSUPPORTED);
                String value=a.getValue(local.equals("Override")?"PartName":"Extension");
                if (value==null || value.isEmpty() || value.length()>512) reject(Reason.CORRUPT);
                String key=local.equals("Override")?value:"extension:"+value;
                if (types.put(key,type)!=null) reject(Reason.CORRUPT);
            } else {
                if (!local.equals("Relationship")) reject(Reason.CORRUPT);
                String id=a.getValue("Id"), type=a.getValue("Type"), target=a.getValue("Target"), mode=a.getValue("TargetMode");
                if (id==null || !ids.add(id) || type==null || target==null) reject(Reason.CORRUPT);
                if (mode!=null && !mode.equals("Internal")) reject(Reason.UNSUPPORTED);
                // All explicit external relationships, including hyperlinks, are conservatively rejected.
                if (target.contains(":") || target.startsWith("//") || target.contains("\\")) reject(Reason.UNSUPPORTED);
                if (type.equals("http://purl.oclc.org/ooxml/officeDocument/relationships/officeDocument")) reject(Reason.UNSUPPORTED);
                targets.add(target);
                if (type.equals(OFFICE_REL)) {
                    if (main!=null) reject(Reason.CORRUPT);
                    if (target.startsWith("/")) target=target.substring(1);
                    try { main=path(target.getBytes(StandardCharsets.UTF_8)); }
                    catch (Failure error) { throw new SAXException(error); }
                }
            }
        }
        @Override public void endElement(String uri,String local,String q) { depth--; }
        @Override public void error(org.xml.sax.SAXParseException e) throws SAXException { throw new SAXException("XML_INVALID"); }
        @Override public void fatalError(org.xml.sax.SAXParseException e) throws SAXException { throw new SAXException("XML_INVALID"); }
    }
    private Kind cfb(RandomAccessFile in) throws IOException { return new Compound(in).inspect(); }
    private final class Compound {
        final RandomAccessFile in;
        final int sector, count, major;
        final int[] fat;
        final boolean[] used;
        final byte[] header;
        final List<Entry> entries=new ArrayList<>();
        boolean fatEnded;
        int[] miniFat, rootChain;
        boolean[] miniUsed;
        long rootSize;
        Compound(RandomAccessFile in) throws IOException {
            this.in=in; header=read(in,0,512); major=u16(header,26);
            if (major!=3 && major!=4) fail(Reason.UNSUPPORTED);
            require(u16(header,28)==0xfffe && u16(header,30)==(major==3?9:12) && u16(header,32)==6 && u32(header,56)==4096);
            sector=1<<u16(header,30);
            require(in.length()%sector==0 && in.length()>=sector*2L);
            count=(int)(in.length()/sector-1); used=new boolean[count];
            if (major==3) require(u32(header,40)==0);
            for (int i=8;i<24;i++) require(header[i]==0);
            for (int i=34;i<40;i++) require(header[i]==0);
            int fatCount=bounded(u32(header,44),count);
            int difatCount=bounded(u32(header,72),count);
            List<Integer> fatSectors=new ArrayList<>();
            for (int i=0;i<109;i++) addFat(fatSectors,i32(header,76+i*4));
            int next=i32(header,68);
            for (int i=0;i<difatCount;i++) {
                tick(); claim(next); byte[] data=sector(next);
                for (int j=0;j<sector/4-1;j++) addFat(fatSectors,i32(data,j*4));
                next=i32(data,sector-4);
            }
            require(next==END && fatSectors.size()==fatCount && fatCount>0);
            // FAT allocation is bounded by source length; no attacker-controlled huge arrays.
            require((long)fatCount*(sector/4)>=count);
            fat=new int[count]; int index=0;
            for (int id : fatSectors) {
                tick(); claim(id); byte[] data=sector(id);
                for (int j=0;j<sector/4;j++) { if (index<count) fat[index]=i32(data,j*4); else require(i32(data,j*4)==FREE); index++; }
            }
            Set<Integer> fatIds=new HashSet<>(fatSectors);
            for (int id : fatSectors) require(fat[id]==-3);
            for (int i=0;i<count;i++) if (used[i] && !fatIds.contains(i)) require(fat[i]==-4);
        }
        private int bounded(long n,int max) throws Failure { require(n<=max); return (int)n; }
        private void addFat(List<Integer> ids,int id) throws Failure {
            if (id==FREE) fatEnded=true;
            else { require(!fatEnded && id>=0 && id<count && ids.size()<count); ids.add(id); }
        }
        private void claim(int id) throws Failure {
            require(id>=0 && id<count && !used[id]); used[id]=true;
        }
        private byte[] sector(int id) throws IOException { require(id>=0 && id<count); return read(in,((long)id+1)*sector,sector); }
        private int[] chain(int start,long size,boolean mini) throws IOException {
            int unit=mini?64:sector;
            long expected=(size+unit-1)/unit;
            require(expected<=(mini?miniUsed.length:count));
            int[] result=new int[(int)expected]; int id=start;
            for (int i=0;i<result.length;i++) {
                tick();
                if (mini) {
                    require(id>=0 && id<miniUsed.length && id<miniFat.length && !miniUsed[id]); miniUsed[id]=true;
                    require((long)id*64+Math.min(64,size-(long)i*64)<=rootSize);
                } else claim(id);
                result[i]=id; id=mini?miniFat[id]:fat[id];
            }
            require(id==END || (size==0 && id==FREE)); return result;
        }
        Kind inspect() throws IOException {
            int dir=i32(header,48), dirSectors=0;
            while (dir!=END) {
                tick(); claim(dir); byte[] data=sector(dir); dirSectors++;
                for (int p=0;p<sector;p+=128) {
                    if (entries.size()>=limits.entries) fail(Reason.LIMIT);
                    entries.add(new Entry(data,p));
                }
                dir=fat[dir];
            }
            require(!entries.isEmpty() && entries.get(0).type==5);
            if (major==4) require(dirSectors==u32(header,40));
            Entry root=entries.get(0); rootSize=root.size;
            require(root.left==FREE && root.right==FREE && root.name.equals("Root Entry"));
            rootChain=chain(root.start,rootSize,false);
            int miniCount=bounded(u32(header,64),count);
            int[] miniChain=chain(i32(header,60),(long)miniCount*sector,false);
            miniFat=new int[miniChain.length*(sector/4)]; int index=0;
            for (int id : miniChain) { byte[] data=sector(id); for (int j=0;j<sector/4;j++) miniFat[index++]=i32(data,j*4); }
            miniUsed=new boolean[(int)((rootSize+63)/64)];
            boolean[] visited=new boolean[entries.size()]; visited[0]=true;
            Map<String,Entry> top=new HashMap<>();
            ArrayDeque<int[]> todo=new ArrayDeque<>(); if (root.child!=FREE) todo.add(new int[]{root.child,0});
            Set<String> siblingNames=new HashSet<>(); long total=0;
            while (!todo.isEmpty()) {
                tick(); int[] link=todo.removeLast(); int id=link[0], parent=link[1];
                require(id>0 && id<entries.size() && !visited[id]); visited[id]=true;
                Entry e=entries.get(id); require(e.type==1 || e.type==2);
                require(siblingNames.add(parent+":"+e.name.toUpperCase(Locale.ROOT)));
                if (parent==0) top.put(e.name.toLowerCase(Locale.ROOT),e);
                String lower=e.name.toLowerCase(Locale.ROOT);
                if (lower.equals("vba") || lower.equals("_vba_project_cur") || lower.equals("macros")) fail(Reason.UNSUPPORTED);
                if (e.left!=FREE) todo.add(new int[]{e.left,parent});
                if (e.right!=FREE) todo.add(new int[]{e.right,parent});
                if (e.type==1) {
                    require(e.size==0);
                    if (e.child!=FREE) todo.add(new int[]{e.child,id});
                } else {
                    require(e.child==FREE);
                    if (e.size>limits.partBytes || e.size>limits.expandedBytes-total) fail(Reason.LIMIT);
                    total+=e.size; e.mini=e.size<4096; e.chain=chain(e.start,e.size,e.mini);
                }
            }
            for (int i=1;i<entries.size();i++) require(entries.get(i).type==0 || visited[i]);
            // Reject allocated orphan sectors and broken references, not just the selected stream.
            for (int i=0;i<count;i++) require(used[i] || fat[i]==FREE);
            for (int i=0;i<miniFat.length;i++) require((i<miniUsed.length && miniUsed[i]) || miniFat[i]==FREE);
            if (top.containsKey("encryptedpackage") && top.containsKey("encryptioninfo")) fail(Reason.PASSWORD);
            Entry word=top.get("worddocument"), workbook=top.get("workbook");
            if (word!=null && workbook!=null) fail(Reason.UNSUPPORTED);
            if (word!=null) {
                byte[] fib=word.read(0,32); require(u16(fib,0)==0xa5ec);
                int version=u16(fib,2), flags=u16(fib,10);
                if ((flags&0x100)!=0) fail(Reason.PASSWORD);
                if ((flags&1)!=0) fail(Reason.UNSUPPORTED); // DOT template, not DOC.
                if (version!=0xc1 && version!=0xd9 && version!=0x101 && version!=0x10c && version!=0x112) fail(Reason.UNSUPPORTED);
                Entry table=top.get((flags&0x200)!=0?"1table":"0table");
                require(table!=null && table.type==2 && table.size>0);
                return Kind.DOC;
            }
            if (workbook!=null) {
                byte[] bof=workbook.read(0,8);
                if (u16(bof,0)!=0x809 || u16(bof,4)!=0x600) fail(Reason.UNSUPPORTED);
                require(u16(bof,2)>=16 && u16(bof,6)==5);
                long p=0; int records=0; boolean eof=false;
                while (p<workbook.size) {
                    tick(); if (++records>1_000_000) fail(Reason.LIMIT);
                    byte[] h=workbook.read(p,4); int type=u16(h,0), n=u16(h,2);
                    require(n<=8224 && n<=workbook.size-p-4);
                    if (type==0x2f) fail(Reason.PASSWORD);
                    // Padding after the last EOF is zero-filled in some producers.
                    if (type==0 && n==0 && eof) {
                        while (p<workbook.size) { byte[] padding=workbook.read(p,(int)Math.min(32768,workbook.size-p));
                            for (byte b : padding) require(b==0); p+=padding.length; tick(); }
                        break;
                    }
                    eof=type==0xa; p+=4L+n;
                }
                require(eof); return Kind.XLS;
            }
            fail(Reason.UNSUPPORTED); return null;
        }
        private final class Entry {
            final int type, left, right, child, start;
            final String name;
            final long size;
            int[] chain; boolean mini;
            Entry(byte[] data,int p) throws Failure {
                type=data[p+66]&255; left=i32(data,p+68); right=i32(data,p+72); child=i32(data,p+76); start=i32(data,p+116);
                if (type==0) { name=""; size=0; return; }
                require(type==1 || type==2 || type==5);
                int n=u16(data,p+64); require(n>=2 && n<=64 && n%2==0 && u16(data,p+n-2)==0);
                name=new String(data,p,n-2,StandardCharsets.UTF_16LE); require(!name.isEmpty() && name.indexOf('\0')<0);
                size=size64(data,p+120);
                if (size>limits.sourceBytes) fail(Reason.LIMIT);
            }
            byte[] read(long offset,int n) throws IOException {
                require(type==2 && offset>=0 && n>=0 && offset<=size-n);
                byte[] result=new byte[n]; int done=0, unit=mini?64:sector;
                while (done<n) {
                    tick(); int id=chain[(int)(offset/unit)], within=(int)(offset%unit), amount=Math.min(n-done,unit-within);
                    long position;
                    if (mini) { long miniOffset=(long)id*64+within;
                        position=((long)rootChain[(int)(miniOffset/sector)]+1)*sector+miniOffset%sector;
                    } else position=((long)id+1)*sector+within;
                    in.seek(position); in.readFully(result,done,amount); done+=amount; offset+=amount;
                }
                return result;
            }
        }
    }
}
