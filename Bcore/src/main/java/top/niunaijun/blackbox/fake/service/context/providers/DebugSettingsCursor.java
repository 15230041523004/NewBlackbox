package top.niunaijun.blackbox.fake.service.context.providers;

import android.database.Cursor;
import android.database.CursorWrapper;
import top.niunaijun.blackbox.utils.DebugStatePolicy;

/** Covers Settings queries as well as the usual GET_global provider calls. */
final class DebugSettingsCursor extends CursorWrapper {
    private final String key;

    DebugSettingsCursor(Cursor cursor, String key) {
        super(cursor);
        this.key = key;
    }

    private boolean masked(int column) {
        if (!DebugStatePolicy.isEnabled() || !"value".equalsIgnoreCase(getColumnName(column))) return false;
        int nameColumn = getColumnIndex("name");
        String name = nameColumn < 0 ? key : super.getString(nameColumn);
        return DebugStatePolicy.isDebugSetting(name);
    }

    @Override public String getString(int column) { return masked(column) ? "0" : super.getString(column); }
    @Override public short getShort(int column) { return masked(column) ? 0 : super.getShort(column); }
    @Override public int getInt(int column) { return masked(column) ? 0 : super.getInt(column); }
    @Override public long getLong(int column) { return masked(column) ? 0 : super.getLong(column); }
    @Override public float getFloat(int column) { return masked(column) ? 0 : super.getFloat(column); }
    @Override public double getDouble(int column) { return masked(column) ? 0 : super.getDouble(column); }
    @Override public byte[] getBlob(int column) { return masked(column) ? new byte[]{'0'} : super.getBlob(column); }
    @Override public boolean isNull(int column) { return masked(column) ? false : super.isNull(column); }
}
