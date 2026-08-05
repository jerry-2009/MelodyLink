package com.melody.melodylink.observer;

import android.annotation.SuppressLint;
import android.app.Application;
import android.bluetooth.BluetoothDevice;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;

import io.github.libxposed.api.XposedInterface;

/**
 * Read-only formatting for hook diagnostics. This class must never invoke a target method or
 * mutate a target object, so observing a call cannot change its outcome.
 */
public final class MethodCallObserver {
    private MethodCallObserver() { }

    public static String event(String message) {
        return "pid=" + android.os.Process.myPid() + " process=" + Application.getProcessName()
                + " thread=" + Thread.currentThread().getName() + " " + message;
    }

    public static String signature(Method method) {
        return method.getDeclaringClass().getName() + "." + method.getName()
                + "/" + method.getParameterTypes().length;
    }

    public static String describeArgs(XposedInterface.Chain chain, int arity) {
        StringBuilder result = new StringBuilder("[");
        for (int i = 0; i < arity; i++) {
            if (i > 0) result.append(", ");
            result.append(describe(chain.getArg(i)));
        }
        return result.append(']').toString();
    }

    @SuppressLint("MissingPermission")
    public static String describe(Object value) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean || value instanceof Character) {
            return value.getClass().getSimpleName() + "(" + value + ")";
        }
        if (value instanceof byte[]) return "byte[" + ((byte[]) value).length + "]";
        if (value instanceof BluetoothDevice) {
            try {
                return "BluetoothDevice(nameHash=" + Integer.toHexString(
                        String.valueOf(((BluetoothDevice) value).getName()).hashCode()) + ")";
            } catch (Throwable ignored) {
                return "BluetoothDevice";
            }
        }
        if (value instanceof String) return "String(hash=" + Integer.toHexString(value.hashCode()) + ")";
        if (value instanceof Collection<?>) {
            return value.getClass().getSimpleName() + "(size=" + ((Collection<?>) value).size() + ")";
        }
        String className = value.getClass().getName();
        if (className.startsWith("com.oplus.melody.")) return describeStateObject(value, className);
        return className;
    }

    public static String compact(Object value) {
        if (value == null) return "null";
        if (value instanceof Collection<?>) return "collection[" + ((Collection<?>) value).size() + "]";
        String text = String.valueOf(value).replace('\n', ' ').replace('\r', ' ');
        return text.length() > 160 ? text.substring(0, 160) + "..." : text;
    }

    private static String describeStateObject(Object value, String className) {
        StringBuilder result = new StringBuilder(className).append('{');
        Class<?> type = value.getClass();
        int fields = 0;
        while (type != null && fields < 24) {
            for (Field field : type.getDeclaredFields()) {
                String name = field.getName().toLowerCase();
                if (!(name.contains("connect") || name.contains("state") || name.contains("command")
                        || name.contains("transfer") || name.contains("payload") || name.contains("data")
                        || name.contains("product") || name.contains("init") || name.contains("event"))) continue;
                try {
                    field.setAccessible(true);
                    result.append(field.getName()).append('=').append(compact(field.get(value))).append(',');
                    if (++fields >= 24) break;
                } catch (Throwable ignored) {
                    // Diagnostic access is best effort and must never affect the target process.
                }
            }
            type = type.getSuperclass();
        }
        return result.append('}').toString();
    }
}
