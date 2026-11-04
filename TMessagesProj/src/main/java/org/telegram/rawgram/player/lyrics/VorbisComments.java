package org.telegram.rawgram.player.lyrics;

import org.telegram.messenger.FileLog;
import org.telegram.rawgram.player.RawPlayerConfig;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Embedded lyrics of FLAC (VORBIS_COMMENT block) and Ogg Vorbis / Opus (comment header) files, which Android's
 * MediaMetadataRetriever doesn't expose. A leading ID3 tag is skipped.
 *
 * <p>Ported from exteraGram (exteraSquad, GPL-2.0-or-later): VorbisComments.
 */
public final class VorbisComments {

    /** In order of preference. */
    private static final String[] LYRICS_KEYS = {"LYRICS", "SYNCEDLYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS"};
    private static final int MAX_COMMENTS_SIZE = 4 * 1024 * 1024;
    private static final int MAX_OGG_PAGES = 64;
    private static final int FLAC_BLOCK_VORBIS_COMMENT = 4;
    private static final int ID3_HEADER_SIZE = 10;
    private static final int ID3_FOOTER_FLAG = 0x10;

    private VorbisComments() {
    }

    /** Hook for {@code OtherAudioInfo}: only the new music player shows lyrics, so the file isn't read otherwise. */
    public static String readLyricsForPlayer(File file) {
        return RawPlayerConfig.getNewPlayerStyle() ? readLyrics(file) : null;
    }

    public static String readLyrics(File file) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            byte[] magic = new byte[4];
            raf.readFully(magic);
            long start = 0;
            if (magic[0] == 'I' && magic[1] == 'D' && magic[2] == '3') {
                byte[] header = new byte[6];
                raf.readFully(header);
                int size = ((header[2] & 0x7f) << 21) | ((header[3] & 0x7f) << 14) | ((header[4] & 0x7f) << 7) | (header[5] & 0x7f);
                start = size + ID3_HEADER_SIZE + ((header[1] & ID3_FOOTER_FLAG) != 0 ? ID3_HEADER_SIZE : 0);
                raf.seek(start);
                raf.readFully(magic);
            }
            byte[] comments;
            if (magic[0] == 'f' && magic[1] == 'L' && magic[2] == 'a' && magic[3] == 'C') {
                comments = readFlacComments(raf);
            } else if (magic[0] == 'O' && magic[1] == 'g' && magic[2] == 'g' && magic[3] == 'S') {
                raf.seek(start);
                comments = readOggComments(raf);
            } else {
                comments = null;
            }
            return comments != null ? findLyrics(comments) : null;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    private static byte[] readFlacComments(RandomAccessFile raf) throws IOException {
        while (true) {
            int type = raf.read();
            if (type < 0) {
                return null;
            }
            int length = (raf.read() << 16) | (raf.read() << 8) | raf.read();
            if (length < 0) {
                return null;
            }
            if ((type & 0x7f) == FLAC_BLOCK_VORBIS_COMMENT) {
                if (length > MAX_COMMENTS_SIZE) {
                    return null;
                }
                byte[] block = new byte[length];
                raf.readFully(block);
                return block;
            }
            if ((type & 0x80) != 0) {
                return null; // the last metadata block
            }
            raf.seek(raf.getFilePointer() + length);
        }
    }

    /** The second packet of the stream (the comment header), reassembled from its Ogg pages. */
    private static byte[] readOggComments(RandomAccessFile raf) throws IOException {
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        byte[] header = new byte[27];
        byte[] segments = new byte[255];
        int packetIndex = 0;
        for (int page = 0; page < MAX_OGG_PAGES; page++) {
            raf.readFully(header);
            if (header[0] != 'O' || header[1] != 'g' || header[2] != 'g' || header[3] != 'S') {
                break;
            }
            int segmentCount = header[26] & 0xff;
            raf.readFully(segments, 0, segmentCount);
            for (int i = 0; i < segmentCount; i++) {
                int size = segments[i] & 0xff;
                if (packetIndex == 1) {
                    if (packet.size() + size > MAX_COMMENTS_SIZE) {
                        return null;
                    }
                    byte[] data = new byte[size];
                    raf.readFully(data);
                    packet.write(data, 0, size);
                } else {
                    raf.seek(raf.getFilePointer() + size);
                }
                if (size < 255) {
                    if (packetIndex == 1) {
                        return stripOggCommentHeader(packet.toByteArray());
                    }
                    packetIndex++;
                }
            }
        }
        return null;
    }

    private static byte[] stripOggCommentHeader(byte[] packet) {
        int skip;
        if (packet.length >= 7 && packet[0] == 3 && new String(packet, 1, 6, StandardCharsets.US_ASCII).equals("vorbis")) {
            skip = 7;
        } else if (packet.length >= 8 && new String(packet, 0, 8, StandardCharsets.US_ASCII).equals("OpusTags")) {
            skip = 8;
        } else {
            return null;
        }
        byte[] result = new byte[packet.length - skip];
        System.arraycopy(packet, skip, result, 0, result.length);
        return result;
    }

    private static String findLyrics(byte[] data) {
        int vendorLength = readInt(data, 0);
        if (vendorLength < 0) {
            return null;
        }
        int offset = vendorLength + 8;
        if (offset > data.length) {
            return null;
        }
        int count = readInt(data, vendorLength + 4);
        String[] found = new String[LYRICS_KEYS.length];
        for (int n = 0; n < count; n++) {
            int length = readInt(data, offset);
            int textStart = offset + 4;
            if (textStart > data.length || length < 0 || textStart + length > data.length) {
                break;
            }
            String comment = new String(data, textStart, length, StandardCharsets.UTF_8);
            int eq = comment.indexOf('=');
            if (eq > 0) {
                String key = comment.substring(0, eq).toUpperCase(Locale.ROOT);
                for (int k = 0; k < LYRICS_KEYS.length; k++) {
                    if (found[k] == null && LYRICS_KEYS[k].equals(key)) {
                        String value = comment.substring(eq + 1).trim();
                        if (!value.isEmpty()) {
                            found[k] = value;
                        }
                    }
                }
            }
            offset = textStart + length;
        }
        for (String value : found) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /** Little-endian; -1 when out of range. */
    private static int readInt(byte[] data, int offset) {
        if (offset + 4 > data.length) {
            return -1;
        }
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8) | ((data[offset + 2] & 0xff) << 16) | ((data[offset + 3] & 0xff) << 24);
    }
}
