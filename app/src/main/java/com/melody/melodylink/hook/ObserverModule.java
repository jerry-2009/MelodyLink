package com.melody.melodylink.hook;

import android.bluetooth.BluetoothDevice;
import android.annotation.SuppressLint;
import android.app.Application;
import android.util.Log;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.Collection;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedInterface;

/** Read-only diagnostics for the 16.8.3 candidate hook chain. */
public final class ObserverModule extends XposedModule {
    private static final String TAG = "MelodyLinkObserver";
    private static final String TARGET = "com.oplus.melody";
    private static final int WF_1000XM3_PRODUCT_ID = 0x067410;
    private volatile int targetAddressHash;

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!TARGET.equals(param.getPackageName())) return;
        try {
            ClassLoader loader = param.getClassLoader();
            hookNamed(loader, "com.oplus.melody.common.util.V", "a", 3, "whitelist");
            hookNamed(loader, "com.oplus.melody.btsdk.api.manager.DeviceInfoManager", "f", 4, "deviceInfo");
            hookNamed(loader, "com.oplus.melody.btsdk.api.manager.DeviceInfoManager", "c", 1, "deviceRegistryAdd");
            hookNamed(loader, "com.oplus.melody.btsdk.api.manager.DeviceInfoManager", "d", 1, "deviceRegistryGet");
            hookNamed(loader, "com.oplus.melody.btsdk.api.manager.DeviceInfoManager", "h", 1, "deviceRegistryLookup");
            hookNamed(loader, "com.oplus.melody.btsdk.api.manager.DeviceInfoManager", "i", 1, "deviceRegistryEnsure");
            hookNamed(loader, "com.oplus.melody.btsdk.api.manager.DeviceInfoManager", "e", 1, "connectionRefresh");
            hookNamed(loader, "com.oplus.melody.btsdk.api.data.DeviceInfo", "setDeviceConnectState", 1, "sppState");
            hookNamed(loader, "com.oplus.melody.btsdk.api.data.DeviceInfo", "setDeviceHeadsetConnectState", 1, "hfpState");
            hookNamed(loader, "com.oplus.melody.btsdk.api.data.DeviceInfo", "setDeviceA2dpConnectState", 1, "a2dpState");
            hookNamed(loader, "com.oplus.melody.btsdk.api.data.DeviceInfo", "setDeviceLeAudioConnectState", 2, "leAudioState");
            hookNamed(loader, "com.oplus.melody.ui.component.detail.DetailMainViewModel", "f", 1, "detailState");
            hookNamed(loader, "com.oplus.melody.btsdk.multidevice.HeadsetCoreService", "u0", 2, "sendPacket");
            hookNamed(loader, "com.oplus.melody.btsdk.multidevice.HeadsetCoreService", "m0", 1, "receiveEvent");
            hookNamed(loader, "com.oplus.melody.model.repository.earphone.U", "L0", 3, "noiseWrite");
            hookNamed(loader, "com.oplus.melody.model.repository.earphone.U", "k1", 1, "stateCallback");
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "observer setup failed", t);
        }
    }

    private void hookNamed(ClassLoader loader, String className, String methodName, int arity, String label) {
        try {
            Class<?> type = Class.forName(className, false, loader);
            Method selected = null;
            for (Method method : type.getDeclaredMethods()) {
                if (method.getName().equals(methodName) && method.getParameterTypes().length == arity) {
                    selected = method;
                    break;
                }
            }
            if (selected == null) {
                log(Log.WARN, TAG, label + " not found: " + className + "." + methodName + "/" + arity);
                return;
            }
            Method method = selected;
            hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                boolean traceCall = shouldTrace(label, chain, arity);
                if (traceCall) {
                    log(Log.INFO, TAG, event(label + " before " + signature(method) + " args=" + describeArgs(chain, arity)));
                }
                try {
                    Object deviceName = arity > 2 ? chain.getArg(2) : null;
                    if ("whitelist".equals(label) && deviceName instanceof String
                            && DeviceProfileMapper.isWf1000Xm3((String) deviceName)) {
                        Object profile = findProfile(chain.getArg(0), DeviceProfileMapper.SONY_WF_1000XM3_PROFILE_ID, DeviceProfileMapper.SONY_WF_1000XM3_PROFILE_NAME);
                        if (profile != null) {
                            log(Log.WARN, TAG, "mapping WF-1000XM3 to OPPO Enco X3 id=067410");
                            return profile;
                        }
                        log(Log.ERROR, TAG, "OPPO Enco X3 profile not found; preserving original result");
                    }
                    Object result = chain.proceed();
                    if ("deviceRegistryGet".equals(label) && result == null && chain.getArg(0) instanceof BluetoothDevice) {
                        BluetoothDevice device = (BluetoothDevice) chain.getArg(0);
                        if (isTargetDevice(device)) {
                            targetAddressHash = device.getAddress().hashCode();
                            result = registerTargetDevice(chain.getThisObject(), device);
                        }
                    }
                    if (traceCall || result != null && "deviceRegistryGet".equals(label)) {
                        log(Log.INFO, TAG, event(label + " after result=" + describe(result)));
                    }
                    return result;
                } catch (Throwable t) {
                    log(Log.ERROR, TAG, label + " original threw", t);
                    throw t;
                }
            });
            log(Log.INFO, TAG, event("hooked " + label + " " + signature(method)));
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cannot hook " + label + " in " + className, t);
        }
    }

    @SuppressLint("MissingPermission")
    private Object registerTargetDevice(Object manager, BluetoothDevice device) {
        try {
            Class<?> managerClass = manager.getClass();
            Method create = managerClass.getDeclaredMethod("f", int.class, BluetoothDevice.class, String.class, String.class);
            create.setAccessible(true);
            Object info = create.invoke(null, WF_1000XM3_PRODUCT_ID, device, device.getAddress(), device.getName());
            Method add = managerClass.getDeclaredMethod("c", info.getClass());
            add.setAccessible(true);
            add.invoke(manager, info);
            log(Log.WARN, TAG, event("registered WF-1000XM3 DeviceInfo through Melody manager"));
            return info;
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "WF-1000XM3 DeviceInfo registration failed", t);
            return null;
        }
    }

    @SuppressLint("MissingPermission")
    private boolean shouldTrace(String label, XposedInterface.Chain chain, int arity) {
        if ("whitelist".equals(label)) {
            return arity > 2 && chain.getArg(2) instanceof String
                    && DeviceProfileMapper.isWf1000Xm3((String) chain.getArg(2));
        }
        if ("deviceRegistryLookup".equals(label)) {
            Object address = chain.getArg(0);
            return address instanceof String && targetAddressHash != 0 && address.hashCode() == targetAddressHash;
        }
        if ("deviceRegistryGet".equals(label) && chain.getArg(0) instanceof BluetoothDevice) {
            return isTargetDevice((BluetoothDevice) chain.getArg(0));
        }
        return true;
    }

    @SuppressLint("MissingPermission")
    private static boolean isTargetDevice(BluetoothDevice device) {
        return DeviceProfileMapper.isWf1000Xm3(device.getName());
    }

    private static Object findProfile(Object value, String id, String name) {
        if (!(value instanceof Collection<?>)) return null;
        for (Object item : (Collection<?>) value) {
            if (item == null) continue;
            Object itemId = readField(item, "id");
            Object itemName = readField(item, "name");
            if (id.equals(String.valueOf(itemId)) && name.equals(String.valueOf(itemName))) return item;
        }
        return null;
    }

    private static Object readField(Object object, String fieldName) {
        Class<?> type = object.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(object);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static String compact(Object value) {
        if (value == null) return "null";
        if (value instanceof Collection<?>) return "collection[" + ((Collection<?>) value).size() + "]";
        String text = String.valueOf(value).replace('\n', ' ').replace('\r', ' ');
        return text.length() > 160 ? text.substring(0, 160) + "..." : text;
    }

    private static String signature(Method method) {
        return method.getDeclaringClass().getName() + "." + method.getName() + "/" + method.getParameterTypes().length;
    }

    private static String describeArgs(XposedInterface.Chain chain, int arity) {
        StringBuilder result = new StringBuilder("[");
        for (int i = 0; i < arity; i++) {
            if (i > 0) result.append(", ");
            result.append(describe(chain.getArg(i)));
        }
        return result.append(']').toString();
    }

    @SuppressLint("MissingPermission")
    private static String describe(Object value) {
        if (value == null) return "null";
        if (value instanceof byte[]) return "byte[" + ((byte[]) value).length + "]";
        if (value instanceof BluetoothDevice) {
            try {
                return "BluetoothDevice(nameHash=" + Integer.toHexString(String.valueOf(((BluetoothDevice) value).getName()).hashCode()) + ")";
            } catch (Throwable ignored) {
                return "BluetoothDevice";
            }
        }
        if (value instanceof String) return "String(hash=" + Integer.toHexString(value.hashCode()) + ")";
        if (value instanceof Collection<?>) return value.getClass().getSimpleName() + "(size=" + ((Collection<?>) value).size() + ")";
        String className = value.getClass().getName();
        if (className.startsWith("com.oplus.melody.")) return describeStateObject(value, className);
        return className;
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
                    Object fieldValue = field.get(value);
                    result.append(field.getName()).append('=').append(compact(fieldValue)).append(',');
                    fields++;
                    if (fields >= 24) break;
                } catch (Throwable ignored) {
                    // Diagnostic access is best effort and must never affect the target process.
                }
            }
            type = type.getSuperclass();
        }
        return result.append('}').toString();
    }

    private static String event(String message) {
        return "pid=" + android.os.Process.myPid() + " process=" + Application.getProcessName()
                + " thread=" + Thread.currentThread().getName() + " " + message;
    }
}
