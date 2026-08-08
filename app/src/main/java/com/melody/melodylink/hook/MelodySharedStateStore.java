package com.melody.melodylink.hook;

import android.app.Application;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Owns shared-file naming so HookModule does not know the storage layout. */
final class MelodySharedStateStore {
    static final String STATE_FILE = ".melodylink_sony_state";
    static final String COMMAND_FILE = ".melodylink_sony_anc_command";
    static final String BATTERY_COMMAND_FILE = ".melodylink_sony_battery_command";

    private final File directory;

    private MelodySharedStateStore(File directory) {
        this.directory = directory;
    }

    static MelodySharedStateStore from(Application application) {
        return application == null ? null : new MelodySharedStateStore(application.getFilesDir());
    }

    File stateFile() {
        return new File(directory, STATE_FILE);
    }

    File commandFile() {
        return new File(directory, COMMAND_FILE);
    }

    File batteryCommandFile() {
        return new File(directory, BATTERY_COMMAND_FILE);
    }

    static SharedState readState(File file) {
        String[] lines = readLines(file, 2);
        if (lines == null) return null;
        try {
            int modeIndex = lines.length >= 3 ? Integer.parseInt(lines[2].trim()) : -1;
            return new SharedState(lines[0].trim(), Integer.parseInt(lines[1].trim()), modeIndex);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static SharedCommand readCommand(File file) {
        String[] lines = readLines(file, 3);
        if (lines == null) return null;
        try {
            return new SharedCommand(lines[0].trim(), Integer.parseInt(lines[1].trim()), lines[2].trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static SharedBatteryCommand readBatteryCommand(File file) {
        String[] lines = readLines(file, 2);
        return lines == null ? null : new SharedBatteryCommand(lines[0].trim(), lines[1].trim());
    }

    static boolean writeState(File file, String address, int ownerPid, int modeIndex) {
        return write(file, address + "\n" + ownerPid + "\n" + modeIndex + "\n");
    }

    static boolean writeCommand(File file, String address, int modeIndex, String nonce) {
        return write(file, address + "\n" + modeIndex + "\n" + nonce + "\n");
    }

    static boolean writeBatteryCommand(File file, String address, String nonce) {
        return write(file, address + "\n" + nonce + "\n");
    }

    static boolean delete(File file) {
        return file == null || !file.exists() || file.delete();
    }

    private static boolean write(File file, String content) {
        if (file == null) return false;
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(content.getBytes(StandardCharsets.US_ASCII));
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String[] readLines(File file, int minimumLines) {
        if (file == null || !file.isFile()) return null;
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[128];
            int count = input.read(buffer);
            if (count <= 0) return null;
            String[] lines = new String(buffer, 0, count, StandardCharsets.US_ASCII).split("\\r?\\n");
            return lines.length >= minimumLines ? lines : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static final class SharedState {
        final String address;
        final int ownerPid;
        final int modeIndex;

        SharedState(String address, int ownerPid, int modeIndex) {
            this.address = address;
            this.ownerPid = ownerPid;
            this.modeIndex = modeIndex;
        }
    }

    static final class SharedCommand {
        final String address;
        final int modeIndex;
        final String nonce;

        SharedCommand(String address, int modeIndex, String nonce) {
            this.address = address;
            this.modeIndex = modeIndex;
            this.nonce = nonce;
        }
    }

    static final class SharedBatteryCommand {
        final String address;
        final String nonce;

        SharedBatteryCommand(String address, String nonce) {
            this.address = address;
            this.nonce = nonce;
        }
    }
}
