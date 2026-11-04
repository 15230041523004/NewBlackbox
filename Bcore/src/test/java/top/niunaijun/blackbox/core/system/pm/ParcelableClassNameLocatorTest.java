package top.niunaijun.blackbox.core.system.pm;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Standalone regression test; run with javac/java without Android or Gradle. */
public final class ParcelableClassNameLocatorTest {
    private static final String MARKER = "top.niunaijun.blackbox.entity.pm.InstallOption";

    public static void main(String[] args) throws Exception {
        findsLastParcelableMarkerAfterNestedMarker();
        rejectsWrongLengthAndMissingTerminator();
        rejectsMissingMarker();
        System.out.println("PASS ParcelableClassNameLocatorTest (3 cases)");
    }

    private static void findsLastParcelableMarkerAfterNestedMarker() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeParcelableMarker(output, MARKER);
        writeIntLe(output, 1);
        writeIntLe(output, 10042);
        int expected = output.size();
        writeParcelableMarker(output, MARKER);
        writeIntLe(output, 1);
        writeIntLe(output, 0);
        byte[] bytes = output.toByteArray();
        check(ParcelableClassNameLocator.findLast(bytes, MARKER) == expected,
                "must locate the final outer InstallOption marker");
    }

    private static void rejectsWrongLengthAndMissingTerminator() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeIntLe(output, MARKER.length() + 1);
        output.write(MARKER.getBytes(StandardCharsets.UTF_16LE));
        output.write(0);
        output.write(0);
        check(ParcelableClassNameLocator.findLast(output.toByteArray(), MARKER) == -1,
                "wrong UTF-16 length must be rejected");

        output.reset();
        writeIntLe(output, MARKER.length());
        output.write(MARKER.getBytes(StandardCharsets.UTF_16LE));
        output.write(1);
        output.write(0);
        check(ParcelableClassNameLocator.findLast(output.toByteArray(), MARKER) == -1,
                "missing UTF-16 terminator must be rejected");
    }

    private static void rejectsMissingMarker() {
        check(ParcelableClassNameLocator.findLast(new byte[] {1, 2, 3, 4}, MARKER) == -1,
                "missing marker must be rejected");
    }

    private static void writeParcelableMarker(ByteArrayOutputStream output, String value) throws IOException {
        writeIntLe(output, value.length());
        output.write(value.getBytes(StandardCharsets.UTF_16LE));
        output.write(0);
        output.write(0);
        while ((output.size() & 3) != 0) output.write(0);
    }

    private static void writeIntLe(ByteArrayOutputStream output, int value) {
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
        output.write((value >>> 16) & 0xff);
        output.write((value >>> 24) & 0xff);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
