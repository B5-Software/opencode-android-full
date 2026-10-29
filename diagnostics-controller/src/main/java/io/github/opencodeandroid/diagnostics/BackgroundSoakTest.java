package io.github.opencodeandroid.diagnostics;

import android.content.*;
import android.os.*;
import androidx.test.platform.app.InstrumentationRegistry;
import io.github.opencodeandroid.diagnostics.protocol.DiagnosticsProtocol;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Instrumentation targets this controller's UID, never the workstation's UID. */
public class BackgroundSoakTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final ComponentName component = new ComponentName(DiagnosticsProtocol.TARGET, DiagnosticsProtocol.SERVICE);
    private final HandlerThread replies = new HandlerThread("diagnostics-replies");
    private Messenger target;
    private final CountDownLatch connected = new CountDownLatch(1);
    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder binder) { target = new Messenger(binder); connected.countDown(); }
        public void onServiceDisconnected(ComponentName name) { target = null; }
    };
    private void connect() throws Exception {
        assertEquals(DiagnosticsProtocol.CONTROLLER, context.getPackageName());
        int targetUid = context.getPackageManager().getApplicationInfo(DiagnosticsProtocol.TARGET, 0).uid;
        assertNotEquals(android.os.Process.myUid(), targetUid);
        replies.start();
        assertTrue(context.bindService(new Intent().setComponent(component), connection,
            Context.BIND_AUTO_CREATE | Context.BIND_WAIVE_PRIORITY | Context.BIND_NOT_FOREGROUND));
        assertTrue(connected.await(10, TimeUnit.SECONDS));
    }
    private JSONObject request(int what, String id) throws Exception {
        CountDownLatch done = new CountDownLatch(1); AtomicReference<String> result = new AtomicReference<>();
        Message message = Message.obtain(null, what);
        Bundle data = new Bundle(); data.putInt("version", DiagnosticsProtocol.VERSION); data.putString("id", id); message.setData(data);
        message.replyTo = new Messenger(new Handler(replies.getLooper()) {
            public void handleMessage(Message reply) { result.set(reply.getData().getString("json")); done.countDown(); }
        });
        assertNotNull(target); target.send(message); assertTrue("Signature-protected broker did not reply", done.await(10, TimeUnit.SECONDS));
        assertNotNull(result.get()); assertTrue(result.get().length() <= 65536); return new JSONObject(result.get());
    }
    private void disconnect() { try { context.unbindService(connection); } catch (Exception ignored) {} replies.quitSafely(); }
    @Test public void controllerHasADifferentUidAndOnlySignatureIpc() throws Exception {
        try { connect(); JSONObject hello = request(DiagnosticsProtocol.HELLO, "");
            assertEquals(DiagnosticsProtocol.TARGET, hello.getString("package")); assertNotEquals(android.os.Process.myUid(), hello.getInt("uid"));
        } finally { disconnect(); }
    }
    @Test public void tenMinuteBackgroundWorkstationSurvivesEightCompilerMemoryWorkers() throws Exception {
        assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("includeBackgroundSoak")));
        String id = UUID.randomUUID().toString(); boolean finished = false;
        try {
            connect();
            context.startForegroundService(new Intent().setComponent(component).setAction(DiagnosticsProtocol.START).putExtra("id", id));
            long deadline = SystemClock.elapsedRealtime() + 18 * 60_000L; long printed = 0;
            JSONObject state;
            do {
                Thread.sleep(2000); state = request(DiagnosticsProtocol.STATUS, id);
                if (SystemClock.elapsedRealtime() - printed > 30000) {
                    printed = SystemClock.elapsedRealtime();
                    Bundle progress = new Bundle(); progress.putString("stream", "\nSOAK " + state.optString("phase") + " elapsedMs=" + state.optLong("elapsedMs") + " controllerUid=" + android.os.Process.myUid() + " targetUid=" + state.optInt("targetUid") + "\n");
                    InstrumentationRegistry.getInstrumentation().sendStatus(2, progress);
                }
                assertTrue("Background scenario timed out", SystemClock.elapsedRealtime() < deadline);
            } while (!(finished = state.getBoolean("finished")));
            Bundle evidence = new Bundle(); evidence.putString("stream", "\nBACKGROUND_RESULT " + state + "\n");
            InstrumentationRegistry.getInstrumentation().sendStatus(2, evidence);
            assertTrue(state.optString("error", "Background scenario failed"), state.getBoolean("passed"));
            assertTrue(state.getBoolean("cleanup")); assertEquals(48, state.getInt("completedCompiles"));
        } finally {
            if (!finished && target != null) { try { request(DiagnosticsProtocol.CANCEL, id); } catch (Exception ignored) {} }
            disconnect();
        }
    }
}
