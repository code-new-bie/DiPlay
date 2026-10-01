package com.shilapi.xcertplay.instrumenthelper;

import java.lang.reflect.Method;
import java.util.Base64;

/**
 * Writes the BYD instrument's generic-music source, play state and song text while running under the
 * adb shell user (uid 2000) through {@code app_process}. DiPlay itself (an ordinary app, uid >=
 * 10000) is refused by the head unit's {@code autoservice}: its {@code checkSetPermission} allows any
 * caller with uid <= 9999 without a permission, but sends every higher uid down the signature-only
 * {@code BYDAUTO_INSTRUMENT_SET} path. The shell user clears that gate, so this tiny helper does the
 * writes the app cannot.
 *
 * <p>It goes straight to {@code BYDAutoInstrumentDevice.setMediaState/setMediaInfo}, the thin
 * forwarders to {@code BYDAutoManager.setInt/setBuffer}. The higher-level {@code sendMusicName} path
 * is skipped on purpose: its {@code MediaStateDelegate} only writes when the caller is the audio
 * focus owner, which the shell process is not.
 *
 * <p>All values come from the head-unit framework (DiLink 4.0 / Android 10): device type {@code
 * 0x3ef}, feature ids source {@code 0x38f00030}, state {@code 0x38f0000a}, text {@code 0x22db1008};
 * text is UTF-16LE with no BOM ("UnicodeLittleUnmarked"), matching {@code sendMusicName}.
 *
 * <p>Usage: {@code app_process ... InstrumentHelperMain <source|-> <state|-> <base64Utf8Text|->}.
 * Each argument is applied in order; "-" skips it. One line per write is printed as {@code
 * stage=rc}; a thrown error is printed as {@code stage=ERR:type}. A one-shot run keeps the protocol
 * trivial and needs no persistent process.
 */
public final class InstrumentHelperMain {
    private static final int DEVICE_TYPE = 0x3ef;
    private static final int FEATURE_SOURCE = 0x38f00030;
    private static final int FEATURE_STATE = 0x38f0000a;
    private static final int FEATURE_INFO = 0x22db1008;

    public static void main(String[] args) {
        Object device;
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("systemMain").invoke(null);
            Object context = activityThread.getMethod("getSystemContext").invoke(thread);
            Class<?> deviceClass =
                    Class.forName("android.hardware.bydauto.instrument.BYDAutoInstrumentDevice");
            device = deviceClass
                    .getMethod("getInstance", Class.forName("android.content.Context"))
                    .invoke(null, context);
        } catch (Throwable error) {
            System.out.println("init=ERR:" + error.getClass().getName()
                    + (error.getCause() != null ? ":" + error.getCause().getClass().getName() : ""));
            return;
        }

        try {
            Method setState = device.getClass()
                    .getMethod("setMediaState", int.class, int.class, int.class);
            Method setInfo = device.getClass()
                    .getMethod("setMediaInfo", int.class, int.class, byte[].class);

            if (args.length > 0 && !"-".equals(args[0])) {
                int rc = (Integer) setState.invoke(
                        device, DEVICE_TYPE, FEATURE_SOURCE, Integer.parseInt(args[0]));
                System.out.println("source=" + rc);
            }
            if (args.length > 1 && !"-".equals(args[1])) {
                int rc = (Integer) setState.invoke(
                        device, DEVICE_TYPE, FEATURE_STATE, Integer.parseInt(args[1]));
                System.out.println("state=" + rc);
            }
            if (args.length > 2 && !"-".equals(args[2])) {
                byte[] text = new String(Base64.getDecoder().decode(args[2]), "UTF-8")
                        .getBytes("UnicodeLittleUnmarked");
                int rc = (Integer) setInfo.invoke(device, DEVICE_TYPE, FEATURE_INFO, text);
                System.out.println("text=" + rc);
            }
        } catch (Throwable error) {
            Throwable cause = error.getCause() != null ? error.getCause() : error;
            System.out.println("write=ERR:" + cause.getClass().getName()
                    + (cause.getMessage() != null ? ":" + cause.getMessage() : ""));
        }
    }

    private InstrumentHelperMain() {}
}
