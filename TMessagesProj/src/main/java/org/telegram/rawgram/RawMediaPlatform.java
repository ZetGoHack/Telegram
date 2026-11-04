package org.telegram.rawgram;

import android.text.TextUtils;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Guesses which Telegram app sent a voice message, a round video or a photo from the traces each client's encoder
 * leaves in the file: the Opus vendor string, the MP4 box layout and handler names, the JPEG JFIF/ICC/quantization
 * header. Only the file already on the device is read (as exteraGram does), nothing is downloaded.
 * <p>
 * Ported from exteraGram ({@code Mp4Fingerprint}, {@code OpusFingerprint}, {@code JpegFingerprint},
 * {@code MediaUtils.getVoicePlatform / getRoundVideoPlatform / getPhotoPlatform}).
 */
public final class RawMediaPlatform {

    private RawMediaPlatform() {
    }

    /** The platform name and the raw fingerprint behind it (copied on long press). */
    public static final class Result {
        public final String platform;
        public final String fingerprint;

        Result(String platform, String fingerprint) {
            this.platform = platform;
            this.fingerprint = fingerprint;
        }
    }

    private static final Pattern LAVF = Pattern.compile("Lavf[0-9]+\\.[0-9]+\\.[0-9]+");
    private static final String UNDEFINED_LANGUAGE = "und";

    /** Whether the message is a kind that can be fingerprinted (voice, round video, photo or image file). */
    public static boolean supports(MessageObject message) {
        return message != null && (message.isVoice() || message.isRoundVideo() || RawMessageDetails.isImage(message));
    }

    /** Reads the local file; null when the platform can't be told. Call off the UI thread. */
    public static Result detect(MessageObject message, File file) {
        if (message == null || file == null || !file.isFile()) {
            return null;
        }
        try {
            if (message.isVoice()) {
                String vendor = Opus.vendor(file);
                String platform = voicePlatform(vendor);
                return platform != null ? new Result(platform, vendor) : null;
            } else if (message.isRoundVideo()) {
                Mp4 mp4 = Mp4.parse(file);
                String platform = roundVideoPlatform(mp4);
                return platform != null ? new Result(platform, mp4.describe()) : null;
            } else {
                Jpeg jpeg = Jpeg.parse(file);
                String platform = photoPlatform(jpeg);
                return platform != null ? new Result(platform, jpeg.describe()) : null;
            }
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    // ---- platform rules (exteraGram's MediaUtils) ----

    static String voicePlatform(String vendor) {
        if (vendor == null) {
            return null;
        }
        switch (vendor) {
            case "libopus unknown":
                return "Telegram X";
            case "libopus 1.3.1-fixed":
                return "macOS";
            case "telegram-web-a":
                return "Web A";
            case "tweb":
                return "Web K";
            case "libopus 1.5.1":
                return "iOS";
            case "libopus unknown-fixed":
                return "Android";
            default:
                return LAVF.matcher(vendor).matches() ? "Desktop" : null;
        }
    }

    static String roundVideoPlatform(Mp4 mp4) {
        if (mp4 == null || mp4.fragmented) {
            return null;
        }
        if ("isom".equals(mp4.majorBrand) && mp4.minorVersion == 0x200
                && mp4.compatibleBrands.size() == 4 && mp4.compatibleBrands.containsAll(Arrays.asList("isom", "iso2", "avc1", "mp41"))) {
            if (mp4.encoder == null && "VideoHandle".equals(mp4.videoHandler) && "SoundHandle".equals(mp4.audioHandler)
                    && "eng".equals(mp4.videoLanguage) && "eng".equals(mp4.audioLanguage)) {
                return "Android";
            }
            if (mp4.encoder != null && LAVF.matcher(mp4.encoder).matches()
                    && "VideoHandler".equals(mp4.videoHandler) && "SoundHandler".equals(mp4.audioHandler)
                    && UNDEFINED_LANGUAGE.equals(mp4.videoLanguage) && UNDEFINED_LANGUAGE.equals(mp4.audioLanguage)) {
                return "Desktop";
            }
        }
        if (mp4.encoder == null && "mp42".equals(mp4.majorBrand) && mp4.minorVersion == 1
                && mp4.compatibleBrands.size() == 3 && mp4.compatibleBrands.containsAll(Arrays.asList("isom", "mp41", "mp42"))
                && "Core Media Video".equals(mp4.videoHandler) && "Core Media Audio".equals(mp4.audioHandler)
                && UNDEFINED_LANGUAGE.equals(mp4.videoLanguage) && UNDEFINED_LANGUAGE.equals(mp4.audioLanguage)) {
            return "iOS";
        }
        return null;
    }

    private static final int ICC_V2_4 = 0x0240;
    private static final int ICC_V4_3 = 0x0430;
    private static final int ICC_V4_4 = 0x0440;
    private static final int JFIF_UNITS_NONE = 0;
    private static final int JFIF_UNITS_DPI = 1;

    static String photoPlatform(Jpeg jpeg) {
        if (jpeg == null || jpeg.jfifUnits < 0) {
            return null;
        }
        boolean skia = jpeg.iccVersion == ICC_V4_3 && jpeg.iccYear == 2016
                || jpeg.iccDescription != null && jpeg.iccDescription.startsWith("Google/Skia/");
        if (jpeg.jfifUnits == JFIF_UNITS_NONE) {
            if (jpeg.jfifDensityX != 1) {
                return null;
            }
            if (skia) {
                return "Android";
            }
            if (jpeg.iccLength == 0) {
                if (jpeg.quality == 72) {
                    return "iOS";
                }
                if (jpeg.quality == 75) {
                    return "macOS";
                }
            }
            return null;
        }
        if (jpeg.jfifUnits != JFIF_UNITS_DPI || jpeg.jfifDensityX != 72) {
            return jpeg.iccVersion == ICC_V2_4 ? "macOS" : "Desktop";
        }
        if (skia) {
            return "Android";
        }
        if (jpeg.iccVersion == ICC_V2_4) {
            return "macOS";
        }
        if (jpeg.iccVersion == ICC_V4_4 && jpeg.iccLength == 480) {
            return "Desktop";
        }
        return null;
    }

    // ---- Ogg Opus: the vendor string of the OpusTags header ----

    static final class Opus {
        private static final int OGG_MAGIC = 0x4F676753; // "OggS"
        private static final int MAX_READ = 256 * 1024;
        private static final int MAX_PACKET = 64 * 1024;
        private static final int FLAG_CONTINUED = 1, FLAG_FIRST = 2, FLAG_LAST = 4;

        static String vendor(File file) throws IOException {
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
                byte[] packet = new byte[MAX_PACKET];
                byte[] lacing = new byte[255];
                int read = 0, packetLength = 0, serial = 0, nextSequence = 0;
                boolean continued = false, headSeen = false;
                while (read + 27 <= MAX_READ) {
                    if (in.readInt() != OGG_MAGIC || in.readUnsignedByte() != 0) {
                        return null;
                    }
                    int flags = in.readUnsignedByte();
                    in.readLong(); // granule position
                    int pageSerial = in.readInt();
                    int sequence = Integer.reverseBytes(in.readInt());
                    in.readInt(); // checksum
                    int segments = in.readUnsignedByte();
                    if (read == 0) {
                        if (flags != FLAG_FIRST || sequence != 0) {
                            return null;
                        }
                        serial = pageSerial;
                    } else if (pageSerial != serial || sequence != nextSequence || (flags & FLAG_FIRST) != 0) {
                        return null;
                    }
                    if (((flags & FLAG_CONTINUED) != 0) != continued || (flags & ~(FLAG_CONTINUED | FLAG_FIRST | FLAG_LAST)) != 0) {
                        return null;
                    }
                    nextSequence = sequence + 1;
                    read += 27 + segments;
                    if (read > MAX_READ) {
                        return null;
                    }
                    in.readFully(lacing, 0, segments);
                    for (int i = 0; i < segments; i++) {
                        int size = lacing[i] & 0xFF;
                        read += size;
                        if (packetLength + size > MAX_PACKET || read > MAX_READ) {
                            return null;
                        }
                        in.readFully(packet, packetLength, size);
                        packetLength += size;
                        continued = size == 255;
                        if (continued) {
                            continue;
                        }
                        if (!headSeen) {
                            if (packetLength < 19 || !startsWith(packet, "OpusHead") || (packet[8] & 0xFF) > 15 || packet[9] == 0) {
                                return null;
                            }
                            headSeen = true;
                            packetLength = 0;
                        } else {
                            return tagsVendor(packet, packetLength);
                        }
                    }
                    if ((flags & FLAG_LAST) != 0) {
                        return null;
                    }
                }
                return null;
            } catch (EOFException e) {
                return null;
            }
        }

        private static String tagsVendor(byte[] packet, int length) {
            if (length < 16 || !startsWith(packet, "OpusTags")) {
                return null;
            }
            ByteBuffer buffer = ByteBuffer.wrap(packet, 8, length - 8).order(ByteOrder.LITTLE_ENDIAN);
            int vendorLength = buffer.getInt();
            if (vendorLength < 0 || vendorLength > buffer.remaining() - 4) {
                return null;
            }
            String vendor = new String(packet, buffer.position(), vendorLength, StandardCharsets.UTF_8);
            buffer.position(buffer.position() + vendorLength);
            int comments = buffer.getInt();
            if (comments < 0 || comments > buffer.remaining() / 4) {
                return null;
            }
            return vendor;
        }
    }

    // ---- MP4: ftyp brands, track handler names and languages, the ©too encoder tag ----

    static final class Mp4 {
        private static final int MAX_BOXES = 4096;

        String majorBrand;
        long minorVersion;
        final List<String> compatibleBrands = new ArrayList<>();
        String encoder;
        boolean fragmented;
        String videoHandler, videoLanguage;
        String audioHandler, audioLanguage;
        private boolean movie;
        private int boxes;

        private static final class Track {
            String type, name, language;
            boolean media;
        }

        static Mp4 parse(File file) {
            try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
                Mp4 mp4 = new Mp4();
                mp4.readBoxes(in, in.length(), "", null);
                if (mp4.majorBrand == null || !mp4.movie || mp4.videoHandler == null || mp4.audioHandler == null
                        || mp4.videoLanguage == null || mp4.audioLanguage == null) {
                    return null;
                }
                return mp4;
            } catch (IOException e) {
                return null;
            }
        }

        private void readBoxes(RandomAccessFile in, long end, String parent, Track track) throws IOException {
            while (in.getFilePointer() < end) {
                long start = in.getFilePointer();
                long left = end - start;
                if (++boxes > MAX_BOXES || left < 8) {
                    throw new IOException("bad box");
                }
                long size = in.readInt() & 0xFFFFFFFFL;
                byte[] four = new byte[4];
                in.readFully(four);
                String type = new String(four, StandardCharsets.ISO_8859_1);
                int header = 8;
                if (size == 1) {
                    if (left < 16) {
                        throw new IOException("bad box");
                    }
                    size = in.readLong();
                    header = 16;
                } else if (size == 0) {
                    size = left;
                }
                if (size < header || size > left) {
                    throw new IOException("bad box");
                }
                long boxEnd = start + size;
                long payload = size - header;

                if (type.equals("ftyp") && parent.isEmpty()) {
                    if (majorBrand != null || payload < 8 || payload > 256 || payload % 4 != 0) {
                        throw new IOException("bad ftyp");
                    }
                    in.readFully(four);
                    majorBrand = new String(four, StandardCharsets.ISO_8859_1);
                    minorVersion = in.readInt() & 0xFFFFFFFFL;
                    while (in.getFilePointer() < boxEnd) {
                        in.readFully(four);
                        compatibleBrands.add(new String(four, StandardCharsets.ISO_8859_1));
                    }
                } else if (type.equals("moov") && parent.isEmpty()) {
                    if (movie) {
                        throw new IOException("two moov");
                    }
                    movie = true;
                    readBoxes(in, boxEnd, type, null);
                } else if (type.equals("trak") && parent.equals("moov")) {
                    Track t = new Track();
                    readBoxes(in, boxEnd, type, t);
                    if ("vide".equals(t.type)) {
                        if (videoHandler != null) {
                            throw new IOException("two video tracks");
                        }
                        videoHandler = t.name;
                        videoLanguage = t.language;
                    } else if ("soun".equals(t.type)) {
                        if (audioHandler != null) {
                            throw new IOException("two audio tracks");
                        }
                        audioHandler = t.name;
                        audioLanguage = t.language;
                    }
                } else if (type.equals("mdia") && parent.equals("trak")) {
                    if (track.media) {
                        throw new IOException("two mdia");
                    }
                    track.media = true;
                    readBoxes(in, boxEnd, type, track);
                } else if (type.equals("hdlr") && parent.equals("mdia")) {
                    if (track.type != null || payload < 24 || payload > 256 || in.readInt() != 0) {
                        throw new IOException("bad hdlr");
                    }
                    in.readInt(); // pre_defined
                    in.readFully(four);
                    track.type = new String(four, StandardCharsets.ISO_8859_1);
                    in.skipBytes(12); // reserved
                    byte[] name = new byte[(int) payload - 24];
                    in.readFully(name);
                    int length = 0;
                    while (length < name.length && name[length] != 0) {
                        length++;
                    }
                    track.name = new String(name, 0, length, StandardCharsets.UTF_8);
                } else if (type.equals("mdhd") && parent.equals("mdia")) {
                    if (track.language != null || payload < 24) {
                        throw new IOException("bad mdhd");
                    }
                    int versionAndFlags = in.readInt();
                    boolean v1 = versionAndFlags == 0x01000000;
                    if (versionAndFlags != 0 && !v1 || payload < (v1 ? 36 : 24)) {
                        throw new IOException("bad mdhd");
                    }
                    in.skipBytes(v1 ? 28 : 16); // times, timescale, duration
                    int code = in.readUnsignedShort();
                    track.language = new String(new char[]{
                            (char) (((code >> 10) & 0x1F) + 0x60),
                            (char) (((code >> 5) & 0x1F) + 0x60),
                            (char) ((code & 0x1F) + 0x60)});
                } else if (type.equals("udta") && parent.equals("moov")) {
                    readBoxes(in, boxEnd, type, null);
                } else if (type.equals("meta") && (parent.equals("udta") || parent.equals("moov"))) {
                    if (payload < 4 || in.readInt() != 0) {
                        throw new IOException("bad meta");
                    }
                    readBoxes(in, boxEnd, type, null);
                } else if (type.equals("ilst") && parent.equals("meta") || type.equals("©too") && parent.equals("ilst")) {
                    readBoxes(in, boxEnd, type, null);
                } else if (type.equals("data") && parent.equals("©too")) {
                    if (encoder != null || payload < 8 || payload > 1024 || in.readInt() != 1) {
                        throw new IOException("bad encoder tag");
                    }
                    in.readInt(); // locale
                    byte[] value = new byte[(int) payload - 8];
                    in.readFully(value);
                    encoder = new String(value, StandardCharsets.UTF_8);
                } else if (type.equals("mvex") || type.equals("moof")) {
                    fragmented = true;
                }
                in.seek(boxEnd);
            }
        }

        String describe() {
            return "ftyp=" + majorBrand + "/" + minorVersion + "/" + TextUtils.join(",", compatibleBrands)
                    + " video=" + videoHandler + "/" + videoLanguage
                    + " audio=" + audioHandler + "/" + audioLanguage
                    + " encoder=" + encoder + " fragmented=" + fragmented;
        }
    }

    // ---- JPEG: JFIF density, ICC profile header, luma quantization quality ----

    static final class Jpeg {
        private static final int MAX_READ = 256 * 1024;
        private static final int SOI = 0xD8, EOI = 0xD9, SOS = 0xDA, TEM = 0x01, RST0 = 0xD0, RST7 = 0xD7;
        private static final int APP0 = 0xE0, APP1 = 0xE1, APP2 = 0xE2, DQT = 0xDB, DHT = 0xC4, DRI = 0xDD;
        private static final int SOF0 = 0xC0, SOF2 = 0xC2;

        private static final int[] ZIGZAG = {
                0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5, 12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28,
                35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51, 58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63};
        /** The luminance table of the JPEG standard (Annex K), quality 50, natural order. */
        private static final int[] STANDARD_LUMA = {
                16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55, 14, 13, 16, 24, 40, 57, 69, 56, 14, 17, 22, 29, 51, 87, 80, 62,
                18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113, 92, 49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100, 103, 99};

        int jfifVersion = -1, jfifUnits = -1, jfifDensityX, jfifDensityY;
        boolean jfifThumbnail, exif, xmp;
        int iccLength, iccVersion, iccYear;
        String iccDescription;
        boolean quantizationTable;
        int quality = -1;
        int huffmanTables;
        boolean restartInterval, progressive;
        int width, height, components, horizontalSampling, verticalSampling;
        String markerOrder = "";

        static Jpeg parse(File file) throws IOException {
            try (InputStream in = new BufferedInputStream(new FileInputStream(file), 16 * 1024)) {
                if (in.read() != 0xFF || in.read() != SOI) {
                    return null;
                }
                Jpeg jpeg = new Jpeg();
                StringBuilder order = new StringBuilder();
                int read = 2;
                while (read < MAX_READ && in.read() == 0xFF) {
                    int marker = in.read();
                    while (marker == 0xFF) {
                        marker = in.read();
                        read++;
                    }
                    read += 2;
                    if (marker < 0 || marker == EOI || marker == SOS) {
                        break;
                    }
                    if (marker == TEM || marker >= RST0 && marker <= RST7) {
                        continue;
                    }
                    int hi = in.read(), lo = in.read();
                    if (hi < 0 || lo < 0) {
                        break;
                    }
                    int length = (hi << 8) | lo;
                    int payload = length - 2;
                    if (payload < 0) {
                        break;
                    }
                    read += length;
                    if (order.length() > 0) {
                        order.append('>');
                    }
                    order.append(String.format(Locale.US, "%02X", marker));
                    boolean wanted = marker == APP0 || marker == APP1 || marker == APP2 || marker == DQT || marker == DHT
                            || marker == DRI || marker >= SOF0 && marker <= SOF2;
                    if (!wanted) {
                        if (!skipFully(in, payload)) {
                            break;
                        }
                        continue;
                    }
                    byte[] data = new byte[payload];
                    if (!readFully(in, data)) {
                        break;
                    }
                    jpeg.readSegment(marker, data);
                }
                jpeg.markerOrder = order.toString();
                return jpeg;
            }
        }

        private static boolean skipFully(InputStream in, long count) throws IOException {
            long skipped = 0;
            while (skipped < count) {
                long n = in.skip(count - skipped);
                if (n <= 0) {
                    return false;
                }
                skipped += n;
            }
            return true;
        }

        private static boolean readFully(InputStream in, byte[] data) throws IOException {
            int done = 0;
            while (done < data.length) {
                int n = in.read(data, done, data.length - done);
                if (n < 0) {
                    return false;
                }
                done += n;
            }
            return true;
        }

        private void readSegment(int marker, byte[] b) {
            if (marker == APP0) {
                if (b.length < 14 || !startsWith(b, "JFIF")) {
                    return;
                }
                jfifVersion = u16(b, 5);
                jfifUnits = b[7] & 0xFF;
                jfifDensityX = u16(b, 8);
                jfifDensityY = u16(b, 10);
                jfifThumbnail = b[12] != 0 || b[13] != 0;
            } else if (marker == APP1) {
                exif |= startsWith(b, "Exif");
                xmp |= startsWith(b, "http://ns.adobe.com/xap/1.0/");
            } else if (marker == APP2) {
                // "ICC_PROFILE\0" + chunk number + chunk count, then the profile header
                if (iccLength == 0 && b.length >= 50 && startsWith(b, "ICC_PROFILE")) {
                    iccLength = s32(b, 14);
                    iccVersion = u16(b, 22);
                    iccYear = u16(b, 38);
                    iccDescription = iccDescription(b, 14);
                }
            } else if (marker == DQT) {
                int pos = 0;
                while (pos < b.length) {
                    int precision = (b[pos] & 0xF0) >> 4;
                    int id = b[pos] & 0x0F;
                    int tableStart = pos + 1;
                    int next = tableStart + (precision == 0 ? 64 : 128);
                    if (next > b.length) {
                        return;
                    }
                    if (id == 0 && precision == 0 && !quantizationTable) {
                        quantizationTable = true;
                        quality = standardQuality(b, tableStart);
                    }
                    pos = next;
                }
            } else if (marker == DHT) {
                int pos = 0;
                while (pos + 17 <= b.length) {
                    int symbols = 0;
                    for (int i = 0; i < 16; i++) {
                        symbols += b[pos + 1 + i] & 0xFF;
                    }
                    huffmanTables++;
                    pos += 17 + symbols;
                }
            } else if (marker == DRI) {
                restartInterval = b.length >= 2 && u16(b, 0) > 0;
            } else if (marker >= SOF0 && marker <= SOF2 && b.length >= 9) {
                progressive = marker == SOF2;
                height = u16(b, 1);
                width = u16(b, 3);
                components = b[5] & 0xFF;
                horizontalSampling = (b[7] & 0xF0) >> 4;
                verticalSampling = b[7] & 0x0F;
            }
        }

        /** The ICC profile's "desc" tag (v2 text description or v4 multi-localized unicode). */
        private static String iccDescription(byte[] b, int base) {
            try {
                int tags = s32(b, base + 128);
                for (int i = 0; i < Math.min(tags, 64); i++) {
                    int entry = base + 132 + i * 12;
                    if (entry + 12 > b.length) {
                        break;
                    }
                    if (b[entry] != 'd' || b[entry + 1] != 'e' || b[entry + 2] != 's' || b[entry + 3] != 'c') {
                        continue;
                    }
                    int offset = s32(b, entry + 4) + base;
                    int size = s32(b, entry + 8);
                    if (offset < base || size < 12 || offset + size > b.length) {
                        return null;
                    }
                    if (b[offset] == 'd') { // "desc": ASCII count + text
                        int length = Math.min(s32(b, offset + 8) - 1, size - 12);
                        return length > 0 ? new String(b, offset + 12, length, StandardCharsets.US_ASCII) : null;
                    } else if (b[offset] == 'm') { // "mluc": first record
                        int length = s32(b, offset + 20);
                        int start = s32(b, offset + 24) + offset;
                        if (length <= 0 || start < offset || start + length > offset + size) {
                            return null;
                        }
                        return new String(b, start, length, StandardCharsets.UTF_16BE);
                    }
                    return null;
                }
            } catch (Exception ignore) {
            }
            return null;
        }

        /** The libjpeg quality whose scaled standard table matches this one, or -1 for a custom table. */
        private static int standardQuality(byte[] b, int start) {
            for (int q = 100; q >= 1; q--) {
                int scale = q < 50 ? 5000 / q : 200 - q * 2;
                boolean match = true;
                for (int i = 0; i < 64 && match; i++) {
                    int value = (STANDARD_LUMA[ZIGZAG[i]] * scale + 50) / 100;
                    value = Math.max(1, Math.min(255, value));
                    match = value == (b[start + i] & 0xFF);
                }
                if (match) {
                    return q;
                }
            }
            return -1;
        }

        String describe() {
            StringBuilder sb = new StringBuilder();
            if (jfifUnits >= 0) {
                sb.append(String.format(Locale.US, "jfif=%d.%02d/u%d/%dx%d", jfifVersion >> 8, jfifVersion & 0xFF, jfifUnits, jfifDensityX, jfifDensityY));
            } else {
                sb.append("jfif=none");
            }
            if (jfifThumbnail) {
                sb.append("/thumb");
            }
            if (iccLength > 0) {
                sb.append(String.format(Locale.US, " icc=%d/%04x/%d/%s", iccLength, iccVersion, iccYear, iccDescription != null ? iccDescription : "?"));
            } else {
                sb.append(" icc=none");
            }
            sb.append(quality > 0 ? " dqt=q" + quality : quantizationTable ? " dqt=custom" : " dqt=none");
            sb.append(String.format(Locale.US, " sof=%s/%dc/%dx%d/%dx%d", progressive ? "prog" : "base", components,
                    horizontalSampling, verticalSampling, width, height));
            sb.append(" dht=").append(huffmanTables);
            if (exif) sb.append(" exif");
            if (xmp) sb.append(" xmp");
            if (restartInterval) sb.append(" dri");
            sb.append(" order=").append(markerOrder);
            return sb.toString();
        }

        private static int u16(byte[] b, int at) {
            return ((b[at] & 0xFF) << 8) | (b[at + 1] & 0xFF);
        }

        private static int s32(byte[] b, int at) {
            return ((b[at] & 0xFF) << 24) | ((b[at + 1] & 0xFF) << 16) | ((b[at + 2] & 0xFF) << 8) | (b[at + 3] & 0xFF);
        }
    }

    private static boolean startsWith(byte[] data, String prefix) {
        if (data.length <= prefix.length()) {
            return false;
        }
        for (int i = 0; i < prefix.length(); i++) {
            if (data[i] != (byte) prefix.charAt(i)) {
                return false;
            }
        }
        return true;
    }
}
