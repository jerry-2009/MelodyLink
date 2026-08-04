package com.melody.melodylink.hook;

import android.bluetooth.BluetoothDevice;
import android.annotation.SuppressLint;
import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.melody.melodylink.sony.SonyTransportAdapter;
import com.op.bttest.sony.SonyAncMode;
import com.op.bttest.sony.SonyAncState;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedInterface;

/** Sony transport bridge and target-scoped diagnostics for Melody 16.8.3. */
public final class ObserverModule extends XposedModule {
    private static final String TAG = "MelodyLinkObserver";
    private static final String TARGET = "com.oplus.melody";
    private static final String SHARED_STATE_FILE = ".melodylink_sony_state";
    private static final int WF_1000XM3_PRODUCT_ID = 0x067410;
    private volatile int targetAddressHash;
    private volatile String targetAddress;
    private volatile Object earphoneRepository;
    private volatile SonyAncState sonyState;
    private volatile ClassLoader melodyClassLoader;
    private volatile CompletableFuture<Object> pendingNoiseWrite;
    private volatile ScheduledExecutorService foregroundStateWatcher;
    private volatile String lastForegroundStateFingerprint;
    private volatile Method noiseReductionItemUpdateMethod;
    private volatile Handler mainHandler;
    private final Map<Object, Boolean> targetNoiseReductionItems =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final SonyTransportAdapter sonyTransport = new SonyTransportAdapter(new SonyTransportAdapter.Listener() {
        @Override
        public void onConnecting() {
            log(Log.INFO, TAG, event("Sony RFCOMM connecting"));
        }

        @Override
        public void onConnected(SonyAncState state) {
            sonyState = state;
            writeSharedSonyState();
            log(Log.INFO, TAG, event("Sony RFCOMM connected; ANC state=" + state.getMode()));
            refreshTargetRepository("Sony connected");
        }

        @Override
        public void onAncWriteResult(boolean success, SonyAncState state, String reason) {
            CompletableFuture<Object> future;
            synchronized (ObserverModule.this) {
                future = pendingNoiseWrite;
                pendingNoiseWrite = null;
            }
            if (success && state != null) {
                sonyState = state;
                writeSharedSonyState();
                refreshTargetRepository("Sony ANC write");
                log(Log.INFO, TAG, event("Sony ANC write result status=0 mode="
                        + SonyModeMapper.toMelodyIndex(state.getMode())));
            }
            if (future == null) return;
            if (success && state != null) {
                Object result = createSetCommandState(0);
                if (result == null) {
                    future.completeExceptionally(new IllegalStateException("Sony ANC result DTO unavailable"));
                } else {
                    future.complete(result);
                }
            } else {
                future.completeExceptionally(new IllegalStateException(reason));
            }
        }

        @Override
        public void onDisconnected() {
            failPendingNoiseWrite("Sony transport disconnected");
            sonyState = null;
            clearSharedSonyState();
            log(Log.INFO, TAG, event("Sony RFCOMM disconnected"));
            refreshTargetRepository("Sony disconnected");
        }

        @Override
        public void onFailed(String reason) {
            failPendingNoiseWrite(reason);
            sonyState = null;
            clearSharedSonyState();
            log(Log.WARN, TAG, event("Sony RFCOMM failed: " + reason));
            refreshTargetRepository("Sony failed");
        }

        @Override
        public void onLog(String message) {
            log(Log.INFO, TAG, event(message));
        }
    });

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!TARGET.equals(param.getPackageName())) return;
        try {
            if (isPrimaryProcess()) clearSharedSonyState();
            ClassLoader loader = param.getClassLoader();
            melodyClassLoader = loader;
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
            hookNamed(loader, "com.oplus.melody.ui.component.detail.DetailMainViewModel", "g", 1, "detailConnectionState");
            hookNamed(loader, "com.oplus.melody.model.repository.earphone.U", "z", 1, "repositoryObserve");
            hookNamed(loader, "com.oplus.melody.model.repository.earphone.U", "y", 1, "repositoryGet");
            hookNamed(loader, "com.oplus.melody.model.repository.earphone.EarphoneDTO", "getConnectionState", 0, "dtoConnectionState");
            hookNamed(loader, "com.oplus.melody.model.repository.earphone.EarphoneDTO", "getAclConnectionState", 0, "dtoAclState");
            hookNamed(loader, "com.oplus.melody.model.repository.earphone.EarphoneDTO", "isSupportSpp", 0, "dtoSupportSpp");
            hookNamed(loader, "com.oplus.melody.model.repository.earphone.EarphoneDTO", "isInitCmdCompleted", 0, "dtoInitCompleted");
            hookNamed(loader, "com.oplus.melody.model.repository.earphone.EarphoneDTO", "getNoiseReductionModeIndex", 0, "dtoNoiseReductionMode");
            hookNamed(loader, "com.oplus.melody.model.repository.earphone.I", "e", 1, "noiseReductionObserverItem");
            hookNamed(loader, "v9.C1576a", "getConnectionState", 0, "detailInfoConnectionState");
            hookNamed(loader, "v9.C1576a", "getHeadsetConnectionState", 0, "detailInfoHeadsetState");
            hookNamed(loader, "v9.C1576a", "getIsSpp", 0, "detailInfoSupportSpp");
            hookNamed(loader, "v9.a", "getConnectionState", 0, "detailInfoConnectionStateActual");
            hookNamed(loader, "v9.a", "getHeadsetConnectionState", 0, "detailInfoHeadsetStateActual");
            hookNamed(loader, "v9.a", "getIsSpp", 0, "detailInfoSupportSppActual");
            hookNamed(loader, "com.oplus.melody.ui.component.detail.opsreduction.a", "getCurrentNoiseReductionModeIndex", 0, "opsNoiseReductionMode");
            hookNamed(loader, "pa.C1405p", "getCurrentNoiseReductionModeIndex", 0, "noiseReductionModeVO");
            hookNamed(loader, "com.oplus.melody.ui.component.detail.noisereduction.NoiseReductionItem", "onAttached", 0, "noiseReductionItemAttached");
            hookNamed(loader, "com.oplus.melody.ui.component.detail.noisereduction.NoiseReductionItem", "onBindViewHolder", 1, "noiseReductionItemBound");
            hookNamed(loader, "com.oplus.melody.ui.component.detail.noisereduction.NoiseReductionItem", "onEarphoneDataChanged", 1, "noiseReductionItemDataChanged");
            hookNamed(loader, "com.oplus.melody.btsdk.multidevice.HeadsetCoreService", "u", 2, "connectToDevice");
            hookNamed(loader, "com.oplus.melody.btsdk.multidevice.HeadsetCoreService", "v", 1, "connectImmediate");
            hookNamed(loader, "com.oplus.melody.btsdk.multidevice.HeadsetCoreService", "u0", 2, "sendPacket");
            hookNamed(loader, "E7.c", "b", 1, "nativeConnectDevice");
            hookNamed(loader, "E7.c", "a", 2, "directConnectSpp");
            hookNamed(loader, "E7.c", "e", 1, "nativeConnectionState");
            hookNamed(loader, "A7.h", "h", 2, "nativeConnectSuccess");
            hookNamed(loader, "A7.h", "e", 2, "nativeConnectFailure");
            hookNamed(loader, "C7.b", "k", 1, "socketFailureBranch");
            hookNamed(loader, "D7.a", "f", 2, "connectionFailureReport");
            hookNamed(loader, "com.oplus.melody.btsdk.multidevice.HeadsetCoreService", "m0", 1, "receiveEvent");
            hookNamed(loader, "com.oplus.melody.model.repository.earphone.U", "L0", 3, "noiseWrite");
            hookNamed(loader, "com.oplus.melody.model.repository.earphone.U", "s0", 2, "noiseModeWrite");
            hookNamed(loader, "com.oplus.melody.model.repository.earphone.EarphoneRepositoryClientImpl", "s0", 2, "noiseModeWriteClient");
            hookNamed(loader, "com.oplus.melody.model.repository.earphone.EarphoneRepositoryClientImpl", "z", 1, "repositoryClientObserve");
            hookNamed(loader, "com.oplus.melody.model.repository.earphone.U", "k1", 1, "stateCallback");
            hookNamed(loader, "com.oplus.melody.model.repository.earphone.U", "x1", 1, "repositoryNotify");
            if (!isPrimaryProcess()) startForegroundStateWatcher();
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
                for (Method method : type.getDeclaredMethods()) {
                    if (method.getName().equalsIgnoreCase(methodName)
                            && method.getParameterTypes().length == arity) {
                        selected = method;
                        log(Log.WARN, TAG, label + " matched case-insensitive method name: "
                                + method.getName());
                        break;
                    }
                }
            }
            if (selected == null) {
                log(Log.WARN, TAG, label + " not found: " + className + "." + methodName + "/" + arity);
                return;
            }
            Method method = selected;
            if ("noiseReductionItemDataChanged".equals(label)) {
                noiseReductionItemUpdateMethod = method;
            }
            hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                boolean traceCall = shouldTrace(label, chain, arity);
                if (traceCall) {
                    log(Log.INFO, TAG, event(label + " before " + signature(method) + " args=" + describeArgs(chain, arity)));
                }
                try {
                    captureRepository(label, chain);
                    if ("noiseReductionObserverItem".equals(label)) {
                        rememberNoiseReductionObserverItem(chain.getThisObject());
                        return chain.proceed();
                    }
                    if ("noiseReductionItemAttached".equals(label)
                            || "noiseReductionItemBound".equals(label)
                            || "noiseReductionItemDataChanged".equals(label)) {
                        Object result = chain.proceed();
                        rememberNoiseReductionItem(chain.getThisObject());
                        return result;
                    }
                    if ("nativeConnectDevice".equals(label) && isTargetDeviceInfo(chain.getArg(0))) {
                        startSonyConnection(chain.getArg(0));
                        log(Log.WARN, TAG, event("bypassed OPPO E7.c.b/C7.b for WF-1000XM3"));
                        return null;
                    }
                    if ("directConnectSpp".equals(label) && isTargetDeviceInfo(chain.getArg(0))) {
                        boolean connect = chain.getArg(1) instanceof Boolean && (Boolean) chain.getArg(1);
                        if (connect) {
                            startSonyConnection(chain.getArg(0));
                        } else {
                            sonyTransport.disconnect();
                        }
                        log(Log.WARN, TAG, event("bypassed OPPO m_spp_le for WF-1000XM3"));
                        return null;
                    }
                    if ("noiseModeWrite".equals(label) && isTargetAddress(chain.getArg(1))) {
                        if (isPrimaryProcess()) {
                            log(Log.INFO, TAG, event("intercepted Sony ANC mode write index=" + chain.getArg(0)));
                            return startSonyNoiseWriteFuture(chain.getArg(0), method.getDeclaringClass().getClassLoader());
                        }
                        log(Log.INFO, TAG, event("Sony ANC mode write reached non-primary repository; preserving provider bridge"));
                    }
                    if ("noiseWrite".equals(label) && isTargetAddress(chain.getArg(1))) {
                        if (hasPendingNoiseWrite()) {
                            log(Log.INFO, TAG, event("ignored duplicate Sony noise update while ANC write is pending"));
                        } else if (sonyTransport.isConnected() && startSonyNoiseWrite(chain.getArg(2))) {
                            log(Log.INFO, TAG, event("routed target noise reduction write to Sony RFCOMM"));
                        } else {
                            log(Log.WARN, TAG, event("blocked target noise reduction write until Sony transport is ready"));
                        }
                        return null;
                    }
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
                    if ("deviceRegistryGet".equals(label) && chain.getArg(0) instanceof BluetoothDevice) {
                        BluetoothDevice device = (BluetoothDevice) chain.getArg(0);
                        if (isTargetDevice(device)) {
                            targetAddressHash = device.getAddress().hashCode();
                            targetAddress = device.getAddress();
                            if (result == null) {
                                result = registerTargetDevice(chain.getThisObject(), device);
                            }
                        }
                    }
                    if (isTargetObject(chain.getThisObject())) {
                        if ("dtoConnectionState".equals(label) && isSonyConnected()) {
                            result = 2;
                        } else if ("dtoAclState".equals(label) && isSonyConnected()) {
                            result = 2;
                        } else if ("dtoInitCompleted".equals(label)) {
                            result = isSonyConnected();
                        } else if ("dtoNoiseReductionMode".equals(label)) {
                            int mode = sonyState == null ? readSharedSonyModeIndex() : SonyModeMapper.toMelodyIndex(sonyState.getMode());
                            if (mode >= 0) result = mode;
                        }
                    }
                    if (isDetailConnectionInfoObject(chain.getThisObject()) && isSonyConnected()) {
                        if (label.startsWith("detailInfoConnectionState")
                                || label.startsWith("detailInfoHeadsetState")) {
                            result = 2;
                        } else if (label.startsWith("detailInfoSupportSpp")) {
                            result = true;
                        }
                    }
                    if (("opsNoiseReductionMode".equals(label) || "noiseReductionModeVO".equals(label))
                            && isSonyConnected()) {
                        int mode = sonyState == null ? readSharedSonyModeIndex() : SonyModeMapper.toMelodyIndex(sonyState.getMode());
                        if (mode >= 0) result = mode;
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
        if (label.startsWith("dto")) {
            return isTargetObject(chain.getThisObject());
        }
        return true;
    }

    @SuppressLint("MissingPermission")
    private void startSonyConnection(Object deviceInfo) {
        try {
            Method addressGetter = deviceInfo.getClass().getMethod("getDeviceAddress");
            Object address = addressGetter.invoke(deviceInfo);
            Method deviceGetter = deviceInfo.getClass().getMethod("getDevice");
            Object device = deviceGetter.invoke(deviceInfo);
            if (!(address instanceof String) || !(device instanceof BluetoothDevice)
                    || !DeviceProfileMapper.isWf1000Xm3(((BluetoothDevice) device).getName())) {
                log(Log.WARN, TAG, event("Sony connection skipped: target DeviceInfo has no WF-1000XM3 BluetoothDevice"));
                return;
            }
            targetAddressHash = address.hashCode();
            targetAddress = (String) address;
            sonyTransport.connect((BluetoothDevice) device);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Sony connection setup failed", t);
        }
    }

    private boolean isTargetDeviceInfo(Object value) {
        if (value == null) return false;
        try {
            Method getter = value.getClass().getMethod("getDevice");
            Object device = getter.invoke(value);
            return device instanceof BluetoothDevice && isTargetDevice((BluetoothDevice) device);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isTargetAddress(Object value) {
        if (!(value instanceof String)) return false;
        String address = (String) value;
        if (targetAddress != null && targetAddress.equalsIgnoreCase(address)) return true;

        String sharedAddress = readSharedSonyAddress();
        if (sharedAddress != null && sharedAddress.equalsIgnoreCase(address)) {
            rememberTargetAddress(address);
            return true;
        }

        if (isWf1000Xm3Address(address)) {
            rememberTargetAddress(address);
            return true;
        }
        return false;
    }

    private boolean startSonyNoiseWrite(Object dto) {
        if (dto == null) return false;
        try {
            Method modeInfo = dto.getClass().getMethod("isNoiseReductionModeInfo");
            if (!(modeInfo.invoke(dto) instanceof Boolean) || !((Boolean) modeInfo.invoke(dto))) {
                return false;
            }
            Method valueGetter = dto.getClass().getMethod("getValue");
            Object value = valueGetter.invoke(dto);
            if (!(value instanceof Integer)) return false;
            int modeIndex = (Integer) value;
            SonyAncMode mode = SonyModeMapper.fromMelodyIndex(modeIndex);
            if (mode == null) return false;
            sonyTransport.setAncMode(mode, 10, false);
            return true;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Sony noise reduction mapping failed", t);
            return false;
        }
    }

    private Object startSonyNoiseWriteFuture(Object rawIndex, ClassLoader loader) {
        CompletableFuture<Object> future = new CompletableFuture<>();
        if (!(rawIndex instanceof Integer)) {
            future.completeExceptionally(new IllegalArgumentException("invalid Sony ANC mode index"));
            return future;
        }
        SonyAncMode mode = SonyModeMapper.fromMelodyIndex((Integer) rawIndex);
        if (mode == null) {
            future.completeExceptionally(new IllegalArgumentException("unsupported Sony ANC mode index"));
            return future;
        }
        if (!sonyTransport.isConnected()) {
            future.completeExceptionally(new IllegalStateException("Sony transport is not connected"));
            return future;
        }
        synchronized (this) {
            CompletableFuture<Object> previous = pendingNoiseWrite;
            if (previous != null && !previous.isDone()) {
                previous.completeExceptionally(new IllegalStateException("Sony ANC write superseded"));
            }
            melodyClassLoader = loader;
            pendingNoiseWrite = future;
        }
        sonyTransport.setAncMode(mode, 10, false);
        return future;
    }

    private synchronized boolean hasPendingNoiseWrite() {
        return pendingNoiseWrite != null && !pendingNoiseWrite.isDone();
    }

    private void failPendingNoiseWrite(String reason) {
        CompletableFuture<Object> future;
        synchronized (this) {
            future = pendingNoiseWrite;
            pendingNoiseWrite = null;
        }
        if (future != null && !future.isDone()) {
            future.completeExceptionally(new IllegalStateException(reason));
        }
    }

    private Object createSetCommandState(int status) {
        ClassLoader loader = melodyClassLoader;
        if (loader == null) return null;
        String packageName = "com.oplus.melody.model.repository.earphone.";
        String[] classNames = {
                packageName + "SetCommandStateDTO",
                packageName + "Z"
        };
        Throwable lastFailure = null;
        for (String className : classNames) {
            try {
                Class<?> type = Class.forName(className, false, loader);
                Constructor<?> constructor = type.getDeclaredConstructor(String.class, int.class);
                constructor.setAccessible(true);
                Object result = constructor.newInstance(targetAddress, status);
                log(Log.INFO, TAG, event("created Sony ANC result DTO " + className
                        + " status=" + status));
                return result;
            } catch (Throwable t) {
                lastFailure = t;
            }
        }
        log(Log.WARN, TAG, "Sony ANC result DTO creation failed", lastFailure);
        return null;
    }

    @SuppressLint("MissingPermission")
    private static boolean isTargetDevice(BluetoothDevice device) {
        return DeviceProfileMapper.isWf1000Xm3(device.getName());
    }

    private boolean isTargetObject(Object value) {
        if (value == null) return false;
        try {
            Method getter = value.getClass().getMethod("getMacAddress");
            Object address = getter.invoke(value);
            return isTargetAddress(address);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isDetailConnectionInfoObject(Object value) {
        if (value == null) return false;
        try {
            Method getter = value.getClass().getMethod("getAddress");
            Object address = getter.invoke(value);
            return address instanceof String && isTargetAddress(address);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isSonyConnected() {
        return targetAddress != null && (sonyTransport.isConnected()
                || targetAddress.equalsIgnoreCase(readSharedSonyAddress()));
    }

    @SuppressLint("MissingPermission")
    private static boolean isWf1000Xm3Address(String address) {
        try {
            BluetoothDevice device = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                    .getRemoteDevice(address);
            return device != null && isTargetDevice(device);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void rememberTargetAddress(String address) {
        targetAddress = address;
        targetAddressHash = address.hashCode();
    }

    private static boolean isPrimaryProcess() {
        return TARGET.equals(Application.getProcessName());
    }

    private static Application currentApplication() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentApplication = activityThread.getDeclaredMethod("currentApplication");
            currentApplication.setAccessible(true);
            Object application = currentApplication.invoke(null);
            return application instanceof Application ? (Application) application : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static File sharedStateFile() {
        Application application = currentApplication();
        return application == null ? null : new File(application.getFilesDir(), SHARED_STATE_FILE);
    }

    private void writeSharedSonyState() {
        if (!isPrimaryProcess() || targetAddress == null) return;
        File file = sharedStateFile();
        if (file == null) return;
        int mode = sonyState == null ? -1 : SonyModeMapper.toMelodyIndex(sonyState.getMode());
        String content = targetAddress + "\n" + android.os.Process.myPid() + "\n" + mode + "\n";
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(content.getBytes(StandardCharsets.US_ASCII));
        } catch (Throwable t) {
            log(Log.WARN, TAG, "shared Sony state write failed", t);
        }
    }

    private void clearSharedSonyState() {
        if (!isPrimaryProcess()) return;
        File file = sharedStateFile();
        if (file != null && file.exists() && !file.delete()) {
            log(Log.WARN, TAG, "shared Sony state delete failed");
        }
    }

    private static String readSharedSonyAddress() {
        SharedSonyState state = readSharedSonyState();
        return state == null ? null : state.address;
    }

    private static int readSharedSonyModeIndex() {
        SharedSonyState state = readSharedSonyState();
        return state == null ? -1 : state.modeIndex;
    }

    private static SharedSonyState readSharedSonyState() {
        File file = sharedStateFile();
        if (file == null || !file.isFile()) return null;
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[128];
            int count = input.read(buffer);
            if (count <= 0) return null;
            String[] lines = new String(buffer, 0, count, StandardCharsets.US_ASCII).split("\\r?\\n");
            if (lines.length < 2) return null;
            int pid = Integer.parseInt(lines[1].trim());
            if (!new File("/proc/" + pid + "/cmdline").isFile()) return null;
            int modeIndex = lines.length >= 3 ? Integer.parseInt(lines[2].trim()) : -1;
            return new SharedSonyState(lines[0].trim(), modeIndex);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static final class SharedSonyState {
        final String address;
        final int modeIndex;

        SharedSonyState(String address, int modeIndex) {
            this.address = address;
            this.modeIndex = modeIndex;
        }
    }

    private void captureRepository(String label, XposedInterface.Chain chain) {
        if (!label.startsWith("repository") && !"noiseWrite".equals(label)) return;
        Object address = null;
        if (("repositoryGet".equals(label) || "repositoryObserve".equals(label)
                || "repositoryClientObserve".equals(label)
                || "repositoryNotify".equals(label)) && chain.getArg(0) instanceof String) {
            address = chain.getArg(0);
        } else if ("noiseWrite".equals(label) && chain.getArg(1) instanceof String) {
            address = chain.getArg(1);
        }
        if (address instanceof String && isTargetAddress(address)) {
            rememberTargetAddress((String) address);
            earphoneRepository = chain.getThisObject();
            if (!isPrimaryProcess() && !"repositoryNotify".equals(label)
                    && !"repositoryClientObserve".equals(label)
                    && readSharedSonyState() != null) {
                refreshTargetRepository("foreground repository observed");
            }
        }
    }

    private synchronized void startForegroundStateWatcher() {
        if (foregroundStateWatcher != null) return;
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "MelodyLinkSonyState");
            thread.setDaemon(true);
            return thread;
        };
        foregroundStateWatcher = Executors.newSingleThreadScheduledExecutor(threadFactory);
        foregroundStateWatcher.scheduleWithFixedDelay(() -> {
            try {
                observeSharedSonyState();
            } catch (Throwable t) {
                log(Log.WARN, TAG, "foreground Sony state watcher failed", t);
            }
        }, 250L, 250L, TimeUnit.MILLISECONDS);
        log(Log.INFO, TAG, event("started foreground Sony state watcher"));
    }

    private void observeSharedSonyState() {
        SharedSonyState state = readSharedSonyState();
        String fingerprint = state == null
                ? null
                : state.address + "\n" + state.modeIndex;
        if (fingerprint == null ? lastForegroundStateFingerprint == null
                : fingerprint.equals(lastForegroundStateFingerprint)) {
            return;
        }
        lastForegroundStateFingerprint = fingerprint;
        if (state != null) {
            rememberTargetAddress(state.address);
        }
        refreshTargetRepository("foreground shared Sony state changed");
        scheduleNoiseReductionItemRefresh("foreground shared Sony state changed");
    }

    private void rememberNoiseReductionItem(Object item) {
        if (item == null) return;
        boolean target = isTargetNoiseReductionItem(item);
        boolean firstObservation;
        synchronized (targetNoiseReductionItems) {
            firstObservation = !targetNoiseReductionItems.containsKey(item);
            targetNoiseReductionItems.put(item, Boolean.TRUE);
        }
        if (firstObservation) {
            Object viewModel = readField(item, "mViewModel");
            Object address = readField(viewModel, "f20659b");
            String addressHash = address instanceof String
                    ? Integer.toHexString(address.hashCode()) : "none";
            log(Log.INFO, TAG, event("captured detail noise reduction item target=" + target
                    + " addressHash=" + addressHash));
        }
        if (target) {
            scheduleNoiseReductionItemRefresh("detail item observed");
        }
    }

    private void rememberNoiseReductionObserverItem(Object observer) {
        Object item = readField(observer, "f19973b");
        if (item != null && item.getClass().getName().equals(
                "com.oplus.melody.ui.component.detail.noisereduction.NoiseReductionItem")) {
            rememberNoiseReductionItem(item);
        }
    }

    private boolean isTargetNoiseReductionItem(Object item) {
        if (item == null) return false;
        Object viewModel = readField(item, "mViewModel");
        Object address = readField(viewModel, "f20659b");
        return isTargetAddress(address);
    }

    private void scheduleNoiseReductionItemRefresh(String reason) {
        try {
            Handler handler = mainHandler;
            if (handler == null) {
                synchronized (this) {
                    handler = mainHandler;
                    if (handler == null) {
                        handler = new Handler(Looper.getMainLooper());
                        mainHandler = handler;
                    }
                }
            }
            Handler targetHandler = handler;
            targetHandler.post(() -> refreshNoiseReductionItems(reason));
        } catch (Throwable t) {
            log(Log.WARN, TAG, "cannot schedule Sony detail ANC refresh", t);
        }
    }

    private void refreshNoiseReductionItems(String reason) {
        SharedSonyState state = readSharedSonyState();
        int mode = state == null ? -1 : state.modeIndex;
        if (mode < 0) return;

        List<Object> items;
        synchronized (targetNoiseReductionItems) {
            items = new ArrayList<>(targetNoiseReductionItems.keySet());
        }
        if (items.isEmpty()) {
            log(Log.WARN, TAG, event("Sony detail ANC refresh has no captured NoiseReductionItem (" + reason + ")"));
        }
        for (Object item : items) {
            if (!isTargetNoiseReductionItem(item)) continue;
            Object noiseReductionVo = readField(item, "mNoiseReductionVO");
            if (noiseReductionVo == null) {
                log(Log.WARN, TAG, event("Sony detail ANC refresh found item without NoiseReductionVO"));
                continue;
            }
            Object current = readField(noiseReductionVo, "mCurrentNoiseReductionModeIndex");
            if (current instanceof Number && ((Number) current).intValue() == mode) continue;
            if (!writeField(noiseReductionVo, "mCurrentNoiseReductionModeIndex", mode)) {
                log(Log.WARN, TAG, event("cannot update Sony detail ANC VO mode"));
                continue;
            }
            invokeNoiseReductionItemUpdate(item, noiseReductionVo, mode, reason);
        }
    }

    private void invokeNoiseReductionItemUpdate(Object item, Object noiseReductionVo,
            int mode, String reason) {
        Method method = noiseReductionItemUpdateMethod;
        if (method == null) {
            method = findMethod(item.getClass(), "onEarphoneDataChanged", 1);
        }
        if (method == null) return;
        try {
            method.setAccessible(true);
            method.invoke(item, noiseReductionVo);
            log(Log.INFO, TAG, event("refreshed Sony detail ANC button mode=" + mode
                    + " (" + reason + ")"));
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Sony detail ANC callback failed", t);
        }
    }

    private void refreshTargetRepository(String reason) {
        Object repository = earphoneRepository;
        String address = targetAddress;
        if (repository == null || address == null) {
            log(Log.WARN, TAG, event("Melody repository refresh skipped: repository not observed (" + reason + ")"));
            return;
        }
        try {
            if (repository.getClass().getName().equals(
                    "com.oplus.melody.model.repository.earphone.EarphoneRepositoryClientImpl")) {
                Method observe = repository.getClass().getDeclaredMethod("z", String.class);
                observe.setAccessible(true);
                Object liveData = observe.invoke(repository, address);
                if (liveData != null) {
                    Method activate = liveData.getClass().getMethod("g");
                    activate.setAccessible(true);
                    activate.invoke(liveData);
                    log(Log.INFO, TAG, event("requested Melody provider refresh for WF-1000XM3 (" + reason + ")"));
                    return;
                }
            }
            Method notifyChanged = repository.getClass().getDeclaredMethod("x1", String.class);
            notifyChanged.setAccessible(true);
            notifyChanged.invoke(repository, address);
            log(Log.INFO, TAG, event("requested Melody repository refresh for WF-1000XM3 (" + reason + ")"));
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Melody repository refresh failed", t);
        }
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
        if (object == null) return null;
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

    private static Method findMethod(Class<?> type, String methodName, int arity) {
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(methodName)
                        && method.getParameterTypes().length == arity) {
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static boolean writeField(Object object, String fieldName, int value) {
        if (object == null) return false;
        Class<?> type = object.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(object, value);
                return true;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (Throwable ignored) {
                return false;
            }
        }
        return false;
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
        if (value instanceof Number || value instanceof Boolean || value instanceof Character) {
            return value.getClass().getSimpleName() + "(" + value + ")";
        }
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
