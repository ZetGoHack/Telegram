package org.telegram.rawgram;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Decoder for the binary tombstone that ApplicationExitInfo.getTraceInputStream() returns for
 * REASON_CRASH_NATIVE (Android 12+). Minimal protobuf reader, no deps.
 *
 * Field numbers follow AOSP system/core/debuggerd/proto/tombstone.proto
 * (https://android.googlesource.com/platform/system/core/+/refs/heads/main/debuggerd/proto/tombstone.proto).
 * Unknown fields are skipped, so newer/older tombstones still decode.
 */
public class RawTombstone {

    // region model

    public static class Frame {
        long relPc, pc, functionOffset;
        String functionName, fileName, buildId;
    }

    public static class Register {
        String name;
        long value;
    }

    public static class TThread {
        int id;
        String name;
        final ArrayList<Register> registers = new ArrayList<>();
        final ArrayList<Frame> frames = new ArrayList<>();
        final ArrayList<String> notes = new ArrayList<>();
    }

    public static class LogLine {
        String timestamp, tag, message;
        int pid, tid, priority;
        String buffer;
    }

    public int arch = -1;
    public String fingerprint, revision, timestamp, selinux, abortMessage;
    public int pid, tid, uid, uptime, pageSize;
    public final ArrayList<String> commandLine = new ArrayList<>();
    public int signalNumber, signalCode;
    public String signalName, signalCodeName;
    public boolean hasFaultAddress, hasSender;
    public long faultAddress;
    public int senderUid, senderPid;
    public final ArrayList<String> causes = new ArrayList<>();
    public final ArrayList<String> crashDetails = new ArrayList<>();
    public final ArrayList<TThread> threads = new ArrayList<>();
    public final ArrayList<LogLine> logs = new ArrayList<>();
    public String error; // set when decoding stopped early

    // endregion

    // region protobuf reader

    private static final class Pb {
        final byte[] b;
        int pos;
        final int end;

        Pb(byte[] b, int off, int len) {
            this.b = b;
            this.pos = off;
            this.end = off + len;
        }

        boolean more() {
            return pos < end;
        }

        long varint() {
            long result = 0;
            int shift = 0;
            while (true) {
                if (pos >= end) {
                    throw new IllegalStateException("truncated varint");
                }
                int x = b[pos++] & 0xff;
                result |= (long) (x & 0x7f) << shift;
                if ((x & 0x80) == 0) {
                    return result;
                }
                shift += 7;
                if (shift > 63) {
                    throw new IllegalStateException("bad varint");
                }
            }
        }

        int length() {
            long len = varint();
            if (len < 0 || len > end - pos) {
                throw new IllegalStateException("bad length " + len);
            }
            return (int) len;
        }

        Pb sub() {
            int len = length();
            Pb p = new Pb(b, pos, len);
            pos += len;
            return p;
        }

        String str() {
            int len = length();
            String s = new String(b, pos, len, StandardCharsets.UTF_8);
            pos += len;
            return s;
        }

        void skip(int wire) {
            switch (wire) {
                case 0:
                    varint();
                    break;
                case 1:
                    pos += 8;
                    break;
                case 2:
                    pos += length();
                    break;
                case 5:
                    pos += 4;
                    break;
                default:
                    throw new IllegalStateException("unsupported wire type " + wire);
            }
            if (pos > end) {
                throw new IllegalStateException("truncated field");
            }
        }
    }

    // endregion

    // region decoding

    public static RawTombstone decode(byte[] data, int length) {
        RawTombstone t = new RawTombstone();
        try {
            Pb p = new Pb(data, 0, length);
            while (p.more()) {
                int tag = (int) p.varint();
                int field = tag >>> 3, wire = tag & 7;
                if (wire == 2) {
                    switch (field) {
                        case 2: t.fingerprint = p.str(); continue;
                        case 3: t.revision = p.str(); continue;
                        case 4: t.timestamp = p.str(); continue;
                        case 8: t.selinux = p.str(); continue;
                        case 9: t.commandLine.add(p.str()); continue;
                        case 10: t.readSignal(p.sub()); continue;
                        case 14: t.abortMessage = p.str(); continue;
                        case 15: t.readCause(p.sub()); continue;
                        case 16: t.readThreadEntry(p.sub()); continue;
                        case 18: t.readLogBuffer(p.sub()); continue;
                        case 21: t.readCrashDetail(p.sub()); continue;
                    }
                } else if (wire == 0) {
                    switch (field) {
                        case 1: t.arch = (int) p.varint(); continue;
                        case 5: t.pid = (int) p.varint(); continue;
                        case 6: t.tid = (int) p.varint(); continue;
                        case 7: t.uid = (int) p.varint(); continue;
                        case 20: t.uptime = (int) p.varint(); continue;
                        case 22: t.pageSize = (int) p.varint(); continue;
                    }
                }
                p.skip(wire);
            }
        } catch (Throwable e) {
            t.error = String.valueOf(e);
        }
        return t;
    }

    private void readSignal(Pb p) {
        while (p.more()) {
            int tag = (int) p.varint();
            int field = tag >>> 3, wire = tag & 7;
            if (wire == 0) {
                long v = p.varint();
                switch (field) {
                    case 1: signalNumber = (int) v; break;
                    case 3: signalCode = (int) v; break;
                    case 5: hasSender = v != 0; break;
                    case 6: senderUid = (int) v; break;
                    case 7: senderPid = (int) v; break;
                    case 8: hasFaultAddress = v != 0; break;
                    case 9: faultAddress = v; break;
                }
            } else if (wire == 2 && field == 2) {
                signalName = p.str();
            } else if (wire == 2 && field == 4) {
                signalCodeName = p.str();
            } else {
                p.skip(wire);
            }
        }
    }

    private void readCause(Pb p) {
        while (p.more()) {
            int tag = (int) p.varint();
            if (tag == (1 << 3 | 2)) {
                causes.add(p.str());
            } else {
                p.skip(tag & 7);
            }
        }
    }

    private void readCrashDetail(Pb p) {
        String name = null, data = null;
        while (p.more()) {
            int tag = (int) p.varint();
            if (tag == (1 << 3 | 2)) {
                name = p.str();
            } else if (tag == (2 << 3 | 2)) {
                data = p.str();
            } else {
                p.skip(tag & 7);
            }
        }
        if (name != null) {
            crashDetails.add(name + ": " + RawCrashLog.oneLine(printable(data), 300));
        }
    }

    /** map<uint32, Thread> entry: key = 1, value = 2. */
    private void readThreadEntry(Pb p) {
        TThread thread = null;
        while (p.more()) {
            int tag = (int) p.varint();
            if (tag == (2 << 3 | 2)) {
                thread = readThread(p.sub());
            } else {
                p.skip(tag & 7);
            }
        }
        if (thread != null) {
            threads.add(thread);
        }
    }

    private static TThread readThread(Pb p) {
        TThread t = new TThread();
        while (p.more()) {
            int tag = (int) p.varint();
            int field = tag >>> 3, wire = tag & 7;
            if (wire == 0 && field == 1) {
                t.id = (int) p.varint();
            } else if (wire == 2 && field == 2) {
                t.name = p.str();
            } else if (wire == 2 && field == 3) {
                t.registers.add(readRegister(p.sub()));
            } else if (wire == 2 && field == 4) {
                t.frames.add(readFrame(p.sub()));
            } else if (wire == 2 && field == 7) {
                t.notes.add(p.str());
            } else {
                p.skip(wire); // memory_dump (5) etc.
            }
        }
        return t;
    }

    private static Register readRegister(Pb p) {
        Register r = new Register();
        while (p.more()) {
            int tag = (int) p.varint();
            if (tag == (1 << 3 | 2)) {
                r.name = p.str();
            } else if (tag == (2 << 3)) {
                r.value = p.varint();
            } else {
                p.skip(tag & 7);
            }
        }
        return r;
    }

    private static Frame readFrame(Pb p) {
        Frame f = new Frame();
        while (p.more()) {
            int tag = (int) p.varint();
            int field = tag >>> 3, wire = tag & 7;
            if (wire == 0) {
                long v = p.varint();
                if (field == 1) {
                    f.relPc = v;
                } else if (field == 2) {
                    f.pc = v;
                } else if (field == 5) {
                    f.functionOffset = v;
                }
            } else if (wire == 2 && field == 4) {
                f.functionName = p.str();
            } else if (wire == 2 && field == 6) {
                f.fileName = p.str();
            } else if (wire == 2 && field == 8) {
                f.buildId = p.str();
            } else {
                p.skip(wire);
            }
        }
        return f;
    }

    private void readLogBuffer(Pb p) {
        String name = null;
        ArrayList<LogLine> lines = new ArrayList<>();
        while (p.more()) {
            int tag = (int) p.varint();
            if (tag == (1 << 3 | 2)) {
                name = p.str();
            } else if (tag == (2 << 3 | 2)) {
                lines.add(readLogLine(p.sub()));
            } else {
                p.skip(tag & 7);
            }
        }
        for (LogLine l : lines) {
            l.buffer = name;
        }
        logs.addAll(lines);
    }

    private static LogLine readLogLine(Pb p) {
        LogLine l = new LogLine();
        while (p.more()) {
            int tag = (int) p.varint();
            int field = tag >>> 3, wire = tag & 7;
            if (wire == 0) {
                long v = p.varint();
                if (field == 2) {
                    l.pid = (int) v;
                } else if (field == 3) {
                    l.tid = (int) v;
                } else if (field == 4) {
                    l.priority = (int) v;
                }
            } else if (wire == 2 && field == 1) {
                l.timestamp = p.str();
            } else if (wire == 2 && field == 5) {
                l.tag = p.str();
            } else if (wire == 2 && field == 6) {
                l.message = p.str();
            } else {
                p.skip(wire);
            }
        }
        return l;
    }

    // endregion

    // region rendering

    public String archName() {
        switch (arch) {
            case 0: return "arm";
            case 1: return "arm64";
            case 2: return "x86";
            case 3: return "x86_64";
            case 4: return "riscv64";
            default: return "unknown(" + arch + ")";
        }
    }

    private boolean is64() {
        return arch == 1 || arch == 3 || arch == 4;
    }

    private String hex(long v) {
        return is64() ? String.format(Locale.US, "%016x", v) : String.format(Locale.US, "%08x", v);
    }

    public TThread crashingThread() {
        for (TThread t : threads) {
            if (t.id == tid) {
                return t;
            }
        }
        return null;
    }

    static boolean isAppFrame(Frame f) {
        return f.fileName != null && f.fileName.contains("libtmessages")
                || f.functionName != null && f.functionName.startsWith("org.telegram.");
    }

    static boolean hasAppFrames(TThread t) {
        for (Frame f : t.frames) {
            if (isAppFrame(f)) {
                return true;
            }
        }
        return false;
    }

    /** "SIGSEGV (SEGV_MAPERR), fault addr 0x0 — null pointer dereference" */
    public String signalSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append(signalName != null ? signalName : "signal " + signalNumber);
        if (signalCodeName != null) {
            sb.append(" (").append(signalCodeName).append(')');
        }
        if (hasFaultAddress) {
            sb.append(", fault addr 0x").append(Long.toHexString(faultAddress));
        }
        if (!causes.isEmpty()) {
            sb.append(" — ").append(causes.get(0));
        } else if (abortMessage != null) {
            sb.append(" — ").append(RawCrashLog.oneLine(abortMessage, 200));
        }
        return sb.toString();
    }

    /** First app frame of the crashing thread, else its #00 frame. */
    public String firstFrame() {
        TThread t = crashingThread();
        if (t == null || t.frames.isEmpty()) {
            return null;
        }
        for (int i = 0; i < t.frames.size(); i++) {
            if (isAppFrame(t.frames.get(i))) {
                return shortFrame(i, t.frames.get(i));
            }
        }
        return shortFrame(0, t.frames.get(0));
    }

    private static String shortFrame(int i, Frame f) {
        String file = f.fileName != null ? f.fileName.substring(f.fileName.lastIndexOf('/') + 1) : "?";
        return String.format(Locale.US, "#%02d %s", i, file) + (f.functionName != null ? " (" + RawCrashLog.oneLine(f.functionName, 160) + "+" + f.functionOffset + ")" : "");
    }

    public String threadLabel() {
        TThread t = crashingThread();
        return (t != null && t.name != null ? t.name : "?") + " (tid " + tid + ")";
    }

    public String frameLine(int i, Frame f) {
        StringBuilder sb = new StringBuilder(160);
        sb.append(String.format(Locale.US, "  #%02d pc ", i)).append(hex(f.relPc)).append("  ");
        sb.append(f.fileName != null ? f.fileName : "<unknown>");
        if (f.functionName != null && !f.functionName.isEmpty()) {
            sb.append(" (").append(RawCrashLog.oneLine(f.functionName, 240));
            if (f.functionOffset != 0) {
                sb.append('+').append(f.functionOffset);
            }
            sb.append(')');
        }
        if (f.buildId != null && !f.buildId.isEmpty() && f.fileName != null && f.fileName.contains("libtmessages")) {
            sb.append(" (BuildId: ").append(f.buildId).append(')');
        }
        return sb.toString();
    }

    /** Crashing thread: signal, cause, backtrace, registers. */
    public void renderStack(StringBuilder sb, int maxFrames) {
        TThread t = crashingThread();
        sb.append("pid: ").append(pid).append(", tid: ").append(tid).append(", name: ").append(t != null ? t.name : "?");
        if (!commandLine.isEmpty()) {
            sb.append("  >>> ").append(commandLine.get(0)).append(" <<<");
        }
        sb.append('\n');
        sb.append("signal ").append(signalNumber).append(" (").append(signalName).append("), code ").append(signalCode)
                .append(" (").append(signalCodeName).append(")");
        if (hasFaultAddress) {
            sb.append(", fault addr 0x").append(hex(faultAddress));
        }
        if (hasSender) {
            sb.append(", from pid ").append(senderPid).append(" uid ").append(senderUid);
        }
        sb.append('\n');
        for (String c : causes) {
            sb.append("Cause: ").append(c).append('\n');
        }
        if (abortMessage != null && !abortMessage.isEmpty()) {
            sb.append("Abort message: '").append(RawCrashLog.oneLine(abortMessage, 1000)).append("'\n");
        }
        for (String d : crashDetails) {
            sb.append("Detail: ").append(d).append('\n');
        }
        if (t == null) {
            sb.append("(поток ").append(tid).append(" не найден в tombstone)\n");
            return;
        }
        for (String note : t.notes) {
            sb.append("note: ").append(note).append('\n');
        }
        sb.append('\n').append("backtrace (").append(t.frames.size()).append(" frames):\n");
        for (int i = 0; i < t.frames.size() && i < maxFrames; i++) {
            sb.append(frameLine(i, t.frames.get(i))).append('\n');
        }
        if (t.frames.size() > maxFrames) {
            sb.append("  … ещё ").append(t.frames.size() - maxFrames).append('\n');
        }
        if (!t.registers.isEmpty()) {
            sb.append('\n').append("registers:\n");
            int col = 0;
            for (Register r : t.registers) {
                sb.append("  ").append(String.format(Locale.US, "%-4s", r.name)).append(' ').append(hex(r.value));
                if (++col == 4) {
                    sb.append('\n');
                    col = 0;
                }
            }
            if (col != 0) {
                sb.append('\n');
            }
        }
    }

    /** Other threads that contain app frames, one "--- item:" per thread. */
    public void renderThreads(StringBuilder sb, int maxFrames, int maxBytes) {
        int start = sb.length();
        int skipped = 0, shown = 0;
        for (TThread t : threads) {
            if (t.id == tid) {
                continue;
            }
            if (!hasAppFrames(t) || sb.length() - start > maxBytes) {
                skipped++;
                continue;
            }
            shown++;
            RawCrashLog.appendItem(sb, "\"" + t.name + "\" tid=" + t.id + " (" + t.frames.size() + " кадров)");
            for (int i = 0; i < t.frames.size() && i < maxFrames; i++) {
                sb.append(frameLine(i, t.frames.get(i))).append('\n');
            }
            if (t.frames.size() > maxFrames) {
                sb.append("  … ещё ").append(t.frames.size() - maxFrames).append('\n');
            }
        }
        if (shown == 0) {
            sb.append("(других потоков с кадрами rawGram нет)\n");
        }
        if (skipped > 0) {
            sb.append("(пропущено потоков без кадров libtmessages/org.telegram: ").append(skipped).append(")\n");
        }
    }

    /** Log lines captured by debuggerd, in logcat threadtime format. */
    public ArrayList<String> logLines() {
        ArrayList<String> out = new ArrayList<>(logs.size());
        for (LogLine l : logs) {
            out.add((l.timestamp != null ? l.timestamp : "?") + " " + l.pid + " " + l.tid + " " + priorityChar(l.priority) + " "
                    + (l.tag != null ? l.tag : "") + ": " + (l.message != null ? l.message : ""));
        }
        return out;
    }

    private static char priorityChar(int p) {
        switch (p) {
            case 2: return 'V';
            case 3: return 'D';
            case 4: return 'I';
            case 5: return 'W';
            case 6: return 'E';
            case 7: return 'F';
            default: return '?';
        }
    }

    public void renderDevice(StringBuilder sb) {
        sb.append("Build fingerprint: ").append(fingerprint).append('\n');
        if (revision != null && !revision.isEmpty()) {
            sb.append("Revision: ").append(revision).append('\n');
        }
        sb.append("ABI: ").append(archName()).append('\n');
        if (timestamp != null) {
            sb.append("Timestamp: ").append(timestamp).append('\n');
        }
        sb.append("Process uptime: ").append(uptime).append("s\n");
        sb.append("Cmdline: ").append(android.text.TextUtils.join(" ", commandLine)).append('\n');
        sb.append("uid: ").append(uid).append('\n');
        if (selinux != null) {
            sb.append("selinux: ").append(selinux).append('\n');
        }
        if (pageSize != 0) {
            sb.append("page size: ").append(pageSize).append('\n');
        }
        sb.append("threads in tombstone: ").append(threads.size()).append(", log lines: ").append(logs.size()).append('\n');
        if (error != null) {
            sb.append("decode stopped early: ").append(error).append('\n');
        }
    }

    private static String printable(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c >= 0x20 && c != 0x7f && c != 0xfffd ? c : '.');
        }
        return sb.toString();
    }

    // endregion
}
