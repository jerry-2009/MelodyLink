package com.melody.melodylink.hook;

import android.bluetooth.BluetoothDevice;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;

import com.melody.melodylink.observer.MethodCallObserver;
import com.melody.melodylink.sony.SonyTransportAdapter;
import com.op.bttest.sony.SonyAncMode;
import com.op.bttest.sony.SonyAncState;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Collection;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedInterface;

/** Sony transport bridge and target-scoped diagnostics for Melody 16.8.3. */
/** Installs behavior-changing hooks for the supported Sony device. */
public final class HookModule extends XposedModule {
    private static final String TAG = "MelodyLinkObserver";
    private static final String TARGET = "com.oplus.melody";
    private static final String SHARED_STATE_FILE = ".melodylink_sony_state";
    private static final String SHARED_COMMAND_FILE = ".melodylink_sony_anc_command";
    private static final int CUSTOM_ANC_CONTROLS_TAG = 0x4D4C4143;
    private static final int WF_1000XM3_PRODUCT_ID = 0x067410;
    private volatile int targetAddressHash;
    private volatile String targetAddress;
    private volatile Object earphoneRepository;
    private volatile SonyAncState sonyState;
    private volatile ClassLoader melodyClassLoader;
    private volatile CompletableFuture<Object> pendingNoiseWrite;
    private volatile ScheduledExecutorService foregroundStateWatcher;
    private volatile String lastForegroundStateFingerprint;
    private volatile String lastSonyCommandFingerprint;
    private final ThreadLocal<Boolean> detailAncWriteObserved = new ThreadLocal<>();
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
            synchronized (HookModule.this) {
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
            if (isPrimaryProcess()) {
                clearSharedSonyState();
                clearSharedSonyCommand();
            }
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
            hookNamed(loader, "com.oplus.melody.ui.component.detail.DetailMainActivity", "onCreate", 1, "detailActivityCreate");
            hookNamed(loader, "com.oplus.melody.model.repository.earphone.U", "z", 1, "repositoryObserve");
            hookNamed(loader, "com.oplus.melody.model.repository.earphone.U", "y", 1, "repositoryGet");
            hookNamed(loader, "com.oplus.melody.model.repository.earphone.U", "g1", 1, "repositoryDtoBuild");
            hookNamed(loader, "com.oplus.melody.model.repository.earphone.EarphoneDTO", "getConnectionState", 0, "dtoConnectionState");
            hookNamed(loader, "com.oplus.melody.model.repository.earphone.EarphoneDTO", "getAclConnectionState", 0, "dtoAclState");
            hookNamed(loader, "com.oplus.melody.model.repository.earphone.EarphoneDTO", "isSupportSpp", 0, "dtoSupportSpp");
            hookNamed(loader, "com.oplus.melody.model.repository.earphone.EarphoneDTO", "isInitCmdCompleted", 0, "dtoInitCompleted");
            hookNamed(loader, "com.oplus.melody.model.repository.earphone.EarphoneDTO", "getNoiseReductionModeIndex", 0, "dtoNoiseReductionMode");
            hookNamed(loader, "v9.C1576a", "getConnectionState", 0, "detailInfoConnectionState");
            hookNamed(loader, "v9.C1576a", "getHeadsetConnectionState", 0, "detailInfoHeadsetState");
            hookNamed(loader, "v9.C1576a", "getIsSpp", 0, "detailInfoSupportSpp");
            hookNamed(loader, "v9.a", "getConnectionState", 0, "detailInfoConnectionStateActual");
            hookNamed(loader, "v9.a", "getHeadsetConnectionState", 0, "detailInfoHeadsetStateActual");
            hookNamed(loader, "v9.a", "getIsSpp", 0, "detailInfoSupportSppActual");
            hookNamed(loader, "com.oplus.melody.ui.component.detail.opsreduction.a", "getCurrentNoiseReductionModeIndex", 0, "opsNoiseReductionMode");
            hookNamed(loader, "pa.C1405p", "getCurrentNoiseReductionModeIndex", 0, "noiseReductionModeVO");
            hookNamed(loader, "com.oplus.melody.ui.component.detail.noisereduction.NoiseReductionItem", "onEarphoneDataChanged", 1, "noiseReductionItemDataChanged");
            hookNamed(loader, "com.oplus.melody.ui.component.detail.noisereduction.NoiseReductionItem$a", "c", 2, "nativeNoiseReductionClick");
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
            hookNamed(loader, "V7.v", "g", 0, "melodyEarphoneLiveDataRequest");
            hookNamed(loader, "V7.u", "handleMessage", 1, "melodyEarphoneLiveDataResponse");
            hookNamed(loader, "com.oplus.melody.model.repository.earphone.U", "k1", 1, "stateCallback");
            hookNamed(loader, "com.oplus.melody.model.repository.earphone.U", "x1", 1, "repositoryNotify");
            hookNamed(loader, "com.oplus.melody.ui.component.detail.opsreduction.OpsReductionItem", "onBindViewHolder", 1, "opsReductionItemBound");
            hookNamed(loader, "com.oplus.melody.ui.component.detail.opsreduction.buttonseekbar.NoiseReductionButtonSeekBarView", "h", 0, "opsReductionSwitchToCurrentMode");
            hookNamed(loader, "com.oplus.melody.ui.component.detail.opsreduction.buttonseekbar.NoiseReductionButtonSeekBarView", "i", 0, "opsReductionUpdateActionView");
            hookNamed(loader, "com.oplus.melody.ui.component.detail.opsreduction.buttonseekbar.NoiseReductionButtonSeekBarView", "d", 0, "opsReductionApplyMode");
            startForegroundStateWatcher();
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "hook setup failed", t);
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
            hook(method)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept(chain -> {
                boolean melodyEarphoneLiveData = isMelodyEarphoneLiveData(chain.getThisObject());
                boolean traceCall = shouldTrace(label, chain, arity);
                if ("melodyEarphoneLiveDataRequest".equals(label)
                        || "melodyEarphoneLiveDataResponse".equals(label)) {
                    traceCall = melodyEarphoneLiveData;
                }
                if (traceCall) {
                    log(Log.INFO, TAG, event(label + " before " + signature(method) + " args=" + describeArgs(chain, arity)));
                }
                try {
                    captureRepository(label, chain);
                    if ("detailActivityCreate".equals(label)) {
                        Object result = chain.proceed();
                        if (chain.getThisObject() instanceof Activity) {
                            scheduleCustomAncControls((Activity) chain.getThisObject(),
                                    method.getDeclaringClass().getClassLoader());
                        }
                        return result;
                    }
                    if ("noiseReductionItemDataChanged".equals(label)) {
                        Object result = chain.proceed();
                        log(Log.INFO, TAG, event("Melody native ANC LiveData callback completed"));
                        return result;
                    }
                    if ("nativeNoiseReductionClick".equals(label)) {
                        int modeIndex = readNativeNoiseReductionMode(chain.getThisObject(), chain.getArg(0));
                        String address = readNativeNoiseReductionAddress(chain.getThisObject());
                        if (isTargetAddress(address) && modeIndex >= 0) {
                            log(Log.INFO, TAG, event("intercepted native ANC click mode=" + modeIndex));
                            dispatchCustomAncWrite(modeIndex, method.getDeclaringClass().getClassLoader());
                            return null;
                        }
                        return chain.proceed();
                    }
                    if ("repositoryDtoBuild".equals(label)) {
                        Object result = chain.proceed();
                        projectSonyAncModeIntoDto(chain.getArg(0), result);
                        return result;
                    }
                    if ("melodyEarphoneLiveDataResponse".equals(label)
                            && melodyEarphoneLiveData) {
                        log(Log.INFO, TAG, event("Melody foreground ANC LiveData response dispatching"));
                        Object result = chain.proceed();
                        Object value = readLiveDataValue(chain.getThisObject());
                        log(Log.INFO, TAG, event("Melody foreground ANC LiveData response published value="
                                + describe(value)));
                        return result;
                    }
                    if ("melodyEarphoneLiveDataRequest".equals(label)
                            && melodyEarphoneLiveData) {
                        log(Log.INFO, TAG, event("Melody foreground ANC LiveData request dispatching"));
                        return chain.proceed();
                    }
                    if ("opsReductionItemBound".equals(label)
                            || "opsReductionSwitchToCurrentMode".equals(label)
                            || "opsReductionUpdateActionView".equals(label)) {
                        Object result = chain.proceed();
                        log(Log.INFO, TAG, event("Melody OPS ANC UI " + label + " completed"));
                        return result;
                    }
                    if ("opsReductionApplyMode".equals(label)) {
                        int modeIndex = readSelectedNoiseReductionMode(chain.getThisObject());
                        String address = readNoiseReductionAddress(chain.getThisObject());
                        log(Log.INFO, TAG, event("ANC apply UI entry mode=" + modeIndex
                                + " target=" + isTargetAddress(address)));
                        detailAncWriteObserved.set(false);
                        Object result;
                        try {
                            result = chain.proceed();
                        } finally {
                            Boolean observed = detailAncWriteObserved.get();
                            detailAncWriteObserved.remove();
                            if (!isPrimaryProcess() && !Boolean.TRUE.equals(observed)
                                    && isTargetAddress(address) && modeIndex >= 0) {
                            log(Log.INFO, TAG, event("forwarding ANC from confirmed detail UI entry mode="
                                    + modeIndex));
                            forwardSonyNoiseWrite(modeIndex, method.getDeclaringClass().getClassLoader());
                            }
                        }
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
                        detailAncWriteObserved.set(true);
                        if (isPrimaryProcess()) {
                            log(Log.INFO, TAG, event("intercepted Sony ANC mode write index=" + chain.getArg(0)));
                            return startSonyNoiseWriteFuture(chain.getArg(0), method.getDeclaringClass().getClassLoader());
                        }
                        log(Log.INFO, TAG, event("forwarding Sony ANC mode write to primary process index="
                                + chain.getArg(0)));
                        return forwardSonyNoiseWrite(chain.getArg(0), method.getDeclaringClass().getClassLoader());
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

    private Object forwardSonyNoiseWrite(Object rawIndex, ClassLoader loader) {
        CompletableFuture<Object> future = new CompletableFuture<>();
        if (!(rawIndex instanceof Integer)) {
            future.completeExceptionally(new IllegalArgumentException("invalid Sony ANC mode index"));
            return future;
        }
        SonyAncMode mode = SonyModeMapper.fromMelodyIndex((Integer) rawIndex);
        String address = targetAddress;
        if (mode == null || address == null) {
            future.completeExceptionally(new IllegalStateException("Sony ANC bridge is not ready"));
            return future;
        }
        melodyClassLoader = loader;
        if (!writeSharedSonyCommand(address, (Integer) rawIndex)) {
            future.completeExceptionally(new IllegalStateException("Sony ANC bridge command failed"));
            return future;
        }
        Object result = createSetCommandState(0);
        if (result == null) {
            future.completeExceptionally(new IllegalStateException("Sony ANC result DTO unavailable"));
        } else {
            future.complete(result);
        }
        return future;
    }

    private void scheduleCustomAncControls(Activity activity, ClassLoader loader) {
        View root = activity.getWindow().getDecorView();
        root.postDelayed(() -> installCustomAncControls(activity, loader), 750L);
        root.postDelayed(() -> installCustomAncControls(activity, loader), 2_000L);
    }

    private void installCustomAncControls(Activity activity, ClassLoader loader) {
        String address = targetAddress;
        if (address == null) address = readSharedSonyAddress();
        if (!isTargetAddress(address)) return;

        ViewGroup root = activity.findViewById(android.R.id.content);
        View nativeControl = findViewByClassName(root,
                "com.oplus.melody.ui.component.detail.opsreduction.buttonseekbar.NoiseReductionButtonSeekBarView");
        if (nativeControl == null || !(nativeControl.getParent() instanceof ViewGroup)) return;
        ViewGroup parent = (ViewGroup) nativeControl.getParent();
        if (parent.findViewWithTag(CUSTOM_ANC_CONTROLS_TAG) != null) return;

        nativeControl.setVisibility(View.GONE);
        LinearLayout controls = new LinearLayout(activity);
        controls.setTag(CUSTOM_ANC_CONTROLS_TAG);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(android.view.Gravity.CENTER);
        controls.setPadding(24, 16, 24, 16);
        controls.addView(createAncButton(activity, "关闭", 0, loader),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        controls.addView(createAncButton(activity, "环境声", 1, loader),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        controls.addView(createAncButton(activity, "降噪", 2, loader),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        parent.addView(controls, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        log(Log.INFO, TAG, event("installed custom Sony ANC controls"));
    }

    private Button createAncButton(Activity activity, String text, int modeIndex, ClassLoader loader) {
        Button button = new Button(activity);
        button.setText(text);
        button.setAllCaps(false);
        button.setOnClickListener(view -> {
            log(Log.INFO, TAG, event("custom Sony ANC click mode=" + modeIndex));
            dispatchCustomAncWrite(modeIndex, loader);
        });
        return button;
    }

    private void dispatchCustomAncWrite(int modeIndex, ClassLoader loader) {
        if (isPrimaryProcess()) {
            startSonyNoiseWriteFuture(modeIndex, loader);
        } else {
            forwardSonyNoiseWrite(modeIndex, loader);
        }
    }

    private static View findViewByClassName(View view, String className) {
        if (view == null) return null;
        if (className.equals(view.getClass().getName())) return view;
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View found = findViewByClassName(group.getChildAt(i), className);
            if (found != null) return found;
        }
        return null;
    }

    private static int readSelectedNoiseReductionMode(Object view) {
        Object selectedValue = readField(view, "s");
        if (!(selectedValue instanceof Integer)) return -1;
        switch ((Integer) selectedValue) {
            case 1: return 0;
            case 2: return 1;
            case 4: return 2;
            case 8: return 3;
            case 16: return 4;
            default: return -1;
        }
    }

    private static int readNativeNoiseReductionMode(Object listener, Object modeItem) {
        if (listener == null || modeItem == null) return -1;
        try {
            Method getPosition = modeItem.getClass().getMethod("f");
            Object positionValue = getPosition.invoke(modeItem);
            if (!(positionValue instanceof String)) return -1;
            int position = Integer.parseInt((String) positionValue);
            Object item = readField(listener, "a");
            Object modes = readField(item, "mNoiseReductionModeList");
            if (!(modes instanceof java.util.List<?>)) return -1;
            java.util.List<?> modeList = (java.util.List<?>) modes;
            if (position < 0 || position >= modeList.size()) return -1;
            Object mode = modeList.get(position);
            Method getProtocolIndex = mode.getClass().getMethod("getProtocolIndex");
            Object protocolIndex = getProtocolIndex.invoke(mode);
            return protocolIndex instanceof Integer ? (Integer) protocolIndex : -1;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static String readNativeNoiseReductionAddress(Object listener) {
        Object item = readField(listener, "a");
        Object viewModel = readField(item, "mViewModel");
        Object address = readField(viewModel, "b");
        return address instanceof String ? (String) address : null;
    }

    private static String readNoiseReductionAddress(Object view) {
        Object bus = readField(view, "f");
        if (bus == null) return null;
        try {
            Method getAddress = bus.getClass().getMethod("getAddress");
            Object address = getAddress.invoke(bus);
            return address instanceof String ? (String) address : null;
        } catch (Throwable ignored) {
            return null;
        }
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

    private static File sharedCommandFile() {
        Application application = currentApplication();
        return application == null ? null : new File(application.getFilesDir(), SHARED_COMMAND_FILE);
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

    private boolean writeSharedSonyCommand(String address, int modeIndex) {
        File file = sharedCommandFile();
        if (file == null) return false;
        String content = address + "\n" + modeIndex + "\n" + System.nanoTime() + "\n";
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            output.write(content.getBytes(StandardCharsets.US_ASCII));
            return true;
        } catch (Throwable t) {
            log(Log.WARN, TAG, "shared Sony ANC command write failed", t);
            return false;
        }
    }

    private void clearSharedSonyCommand() {
        File file = sharedCommandFile();
        if (file != null && file.exists() && !file.delete()) {
            log(Log.WARN, TAG, "shared Sony ANC command delete failed");
        }
    }

    private static SharedSonyCommand readSharedSonyCommand() {
        File file = sharedCommandFile();
        if (file == null || !file.isFile()) return null;
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[128];
            int count = input.read(buffer);
            if (count <= 0) return null;
            String[] lines = new String(buffer, 0, count, StandardCharsets.US_ASCII).split("\\r?\\n");
            if (lines.length < 3) return null;
            return new SharedSonyCommand(lines[0].trim(), Integer.parseInt(lines[1].trim()), lines[2].trim());
        } catch (Throwable ignored) {
            return null;
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

    private static final class SharedSonyCommand {
        final String address;
        final int modeIndex;
        final String nonce;

        SharedSonyCommand(String address, int modeIndex, String nonce) {
            this.address = address;
            this.modeIndex = modeIndex;
            this.nonce = nonce;
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
        if (isPrimaryProcess()) observeSharedSonyCommand();
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
        log(Log.INFO, TAG, event("foreground Sony state changed; requesting native Melody LiveData refresh"
                + " mode=" + (state == null ? -1 : state.modeIndex)));
        refreshTargetRepository("foreground shared Sony state changed");
    }

    private void observeSharedSonyCommand() {
        SharedSonyCommand command = readSharedSonyCommand();
        if (command == null) return;
        String fingerprint = command.address + "\n" + command.modeIndex + "\n" + command.nonce;
        if (fingerprint.equals(lastSonyCommandFingerprint)) return;
        lastSonyCommandFingerprint = fingerprint;
        if (!isTargetAddress(command.address)) {
            log(Log.WARN, TAG, event("ignored Sony ANC command for a different device"));
            return;
        }
        log(Log.INFO, TAG, event("executing forwarded Sony ANC mode write index=" + command.modeIndex));
        startSonyNoiseWriteFuture(command.modeIndex, melodyClassLoader);
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
                    log(Log.INFO, TAG, event("requested native Melody foreground LiveData reload"
                            + " for WF-1000XM3 (" + reason + ")"));
                    return;
                }
            }
            Method notifyChanged = repository.getClass().getDeclaredMethod("x1", String.class);
            notifyChanged.setAccessible(true);
            notifyChanged.invoke(repository, address);
            log(Log.INFO, TAG, event("published native Melody repository update for WF-1000XM3"
                    + " (" + reason + ")"));
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

    private void projectSonyAncModeIntoDto(Object address, Object dto) {
        if (!isTargetAddress(address) || dto == null) return;

        int mode = sonyState == null
                ? readSharedSonyModeIndex()
                : SonyModeMapper.toMelodyIndex(sonyState.getMode());
        if (mode < 0) {
            log(Log.WARN, TAG, event("Melody DTO ANC projection skipped: Sony mode unavailable"));
            return;
        }

        Object previous = readField(dto, "noiseReductionModeIndex");
        if (previous instanceof Number && ((Number) previous).intValue() == mode) {
            log(Log.INFO, TAG, event("Melody DTO ANC projection unchanged mode=" + mode));
            return;
        }
        if (writeIntField(dto, "noiseReductionModeIndex", mode)) {
            log(Log.INFO, TAG, event("projected Sony ANC mode into Melody EarphoneDTO old="
                    + compact(previous) + " new=" + mode));
        } else {
            log(Log.WARN, TAG, event("Melody DTO ANC projection failed old="
                    + compact(previous) + " requested=" + mode));
        }
    }

    private static boolean writeIntField(Object object, String fieldName, int value) {
        if (object == null) return false;
        Class<?> type = object.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.setInt(object, value);
                return field.getInt(object) == value;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (Throwable ignored) {
                return false;
            }
        }
        return false;
    }

    private static boolean isMelodyEarphoneLiveData(Object value) {
        Object liveData = value;
        Object requestCode = readField(liveData, "l");
        if (!(requestCode instanceof Number)) {
            liveData = readField(value, "b");
            requestCode = readField(liveData, "l");
        }
        return requestCode instanceof Number && ((Number) requestCode).intValue() == 0xBDD;
    }

    private static Object readLiveDataValue(Object callback) {
        Object liveData = readField(callback, "b");
        return readField(liveData, "p");
    }

    private static String compact(Object value) {
        return MethodCallObserver.compact(value);
    }

    private static String signature(Method method) {
        return MethodCallObserver.signature(method);
    }

    private static String describeArgs(XposedInterface.Chain chain, int arity) {
        return MethodCallObserver.describeArgs(chain, arity);
    }

    private static String describe(Object value) {
        return MethodCallObserver.describe(value);
    }

    private static String event(String message) {
        return MethodCallObserver.event(message);
    }
}
