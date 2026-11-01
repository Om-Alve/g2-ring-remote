package io.github.omalve.g2ringremote;

import android.os.FileObserver;
import android.system.Os;
import android.system.OsConstants;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the ring's /dev/input nodes and holds an exclusive EVIOCGRAB on each.
 * While a node is grabbed the kernel delivers its events only to this process,
 * so Android's input pipeline never sees them: no touches, no media or volume
 * keys, no wake. Exiting closes the descriptors, which releases the grabs.
 *
 * <p>Nodes come and go as the ring sleeps and reconnects. inotify on /dev/input
 * catches them immediately; a slow rescan is the safety net.
 */
final class DeviceWatcher {
    /** _IOW('E', 0x90, int) */
    private static final int EVIOCGRAB = 0x40044590;
    private static final String INPUT_DIR = "/dev/input";
    private static final Pattern NAME = Pattern.compile("(?m)^\\s*name:\\s*\"(.*)\"\\s*$");
    private static final Pattern UNIQ = Pattern.compile("(?m)^\\s*id:\\s*\"(.*)\"\\s*$");
    private static final int EVENT_SIZE = System.getProperty("os.arch", "").contains("64") ? 24 : 16;

    static final class Node {
        final String path;
        final String name;
        final String uniq;
        final FileDescriptor fd;

        Node(String path, String name, String uniq, FileDescriptor fd) {
            this.path = path;
            this.name = name;
            this.uniq = uniq;
            this.fd = fd;
        }
    }

    private final Config config;
    private final Router router;
    private final Object os;
    private final Method ioctlInt;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final Map<String, Node> grabbed = new ConcurrentHashMap<>();
    /** Nodes identified as some other device; cleared when inotify reports a change. */
    private final Set<String> ignored = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private final Map<String, String> grabErrors = new ConcurrentHashMap<>();
    private FileObserver observer; // strong reference: a collected observer stops watching

    DeviceWatcher(Config config, Router router) throws ReflectiveOperationException {
        this.config = config;
        this.router = router;
        // Android has no public ioctl; libcore's is reachable from app_process.
        this.os = Class.forName("libcore.io.Libcore").getField("os").get(null);
        Method found = null;
        for (Method m : os.getClass().getMethods()) {
            if (m.getName().equals("ioctlInt")) {
                found = m;
            }
        }
        if (found == null) {
            throw new NoSuchMethodException("libcore.io.Os.ioctlInt");
        }
        this.ioctlInt = found;
    }

    void start() {
        observer = new FileObserver(new File(INPUT_DIR),
                FileObserver.CREATE | FileObserver.DELETE | FileObserver.ATTRIB) {
            @Override
            public void onEvent(int event, String name) {
                if (name == null || !name.startsWith("event")) {
                    return;
                }
                final String path = INPUT_DIR + "/" + name;
                ignored.remove(path);
                if ((event & FileObserver.DELETE) != 0) {
                    worker.execute(() -> forget(path));
                } else {
                    // CREATE, or ATTRIB once ueventd has fixed the node's permissions.
                    worker.execute(() -> consider(path));
                }
            }
        };
        observer.startWatching();
        timer.scheduleWithFixedDelay(() -> worker.execute(this::rescan), 0, 5, TimeUnit.SECONDS);
    }

    List<Node> grabbedNodes() {
        List<Node> nodes = new ArrayList<>(grabbed.values());
        Collections.sort(nodes, (a, b) -> a.path.compareTo(b.path));
        return nodes;
    }

    private void rescan() {
        String[] names = new File(INPUT_DIR).list();
        if (names == null) {
            return;
        }
        List<String> present = new ArrayList<>();
        for (String name : names) {
            if (name.startsWith("event")) {
                present.add(INPUT_DIR + "/" + name);
            }
        }
        ignored.retainAll(present);
        for (String path : present) {
            consider(path);
        }
    }

    private void consider(String path) {
        if (grabbed.containsKey(path) || ignored.contains(path)) {
            return;
        }
        String info = identify(path);
        Matcher name = NAME.matcher(info);
        if (!name.find()) {
            ignored.add(path); // unreadable for now; inotify ATTRIB will retry
            return;
        }
        Matcher uniqMatch = UNIQ.matcher(info);
        String uniq = uniqMatch.find() ? uniqMatch.group(1) : "";
        if (!config.matches(name.group(1), uniq)) {
            ignored.add(path);
            return;
        }
        grab(path, name.group(1), uniq);
    }

    private static String identify(String path) {
        // getevent opens the node with the ioctls we cannot reach from Java (EVIOCGNAME, EVIOCGUNIQ).
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                Process p = new ProcessBuilder("getevent", "-i", path).redirectErrorStream(true).start();
                String out = readAll(p.getInputStream());
                if (!p.waitFor(2, TimeUnit.SECONDS)) {
                    p.destroy();
                }
                if (NAME.matcher(out).find()) {
                    return out;
                }
            } catch (IOException | InterruptedException e) {
                // fall through and retry
            }
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                return "";
            }
        }
        return "";
    }

    private void grab(String path, String name, String uniq) {
        FileDescriptor fd;
        try {
            fd = Os.open(path, OsConstants.O_RDONLY | OsConstants.O_CLOEXEC, 0);
        } catch (Exception e) {
            Log.i("could not open " + path + " (" + name + "): " + e);
            return;
        }
        try {
            if (ioctlInt.getParameterCount() == 2) {
                ioctlInt.invoke(os, fd, EVIOCGRAB);
            } else {
                Object one = Class.forName("android.system.Int32Ref").getConstructor(int.class).newInstance(1);
                ioctlInt.invoke(os, fd, EVIOCGRAB, one);
            }
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            String message = "could not grab " + path + " (" + name + "): " + cause;
            // EBUSY means another process holds the grab; the rescan retries, logging once.
            if (!message.equals(grabErrors.put(path, message))) {
                Log.i(message);
            }
            closeQuietly(fd);
            return;
        }
        grabErrors.remove(path);
        final Node node = new Node(path, name, uniq, fd);
        grabbed.put(path, node);
        Log.i("grabbed " + path + " \"" + name + "\" " + uniq);
        Thread reader = new Thread(() -> read(node), "read-" + path.substring(INPUT_DIR.length() + 1));
        reader.setDaemon(true);
        reader.start();
    }

    private void read(Node node) {
        byte[] buf = new byte[EVENT_SIZE * 64];
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.nativeOrder());
        String why = "end of stream";
        try (FileInputStream in = new FileInputStream(node.fd)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                long now = System.currentTimeMillis();
                for (int off = 0; off + EVENT_SIZE <= n; off += EVENT_SIZE) {
                    int base = off + EVENT_SIZE - 8; // skip struct timeval
                    int type = bb.getShort(base) & 0xffff;
                    int code = bb.getShort(base + 2) & 0xffff;
                    int value = bb.getInt(base + 4);
                    router.onEvent(node.path, type, code, value, now);
                }
            }
        } catch (IOException e) {
            why = e.getMessage();
        }
        if (grabbed.remove(node.path, node)) {
            router.forget(node.path);
            Log.i("released " + node.path + " \"" + node.name + "\": " + why);
        }
    }

    private void forget(String path) {
        Node node = grabbed.remove(path);
        if (node != null) {
            router.forget(path);
            Log.i("released " + path + " \"" + node.name + "\": node removed (ring disconnected)");
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > 256 * 1024) {
                break;
            }
        }
        return out.toString("UTF-8");
    }

    private static void closeQuietly(FileDescriptor fd) {
        try {
            Os.close(fd);
        } catch (Exception ignored) {
            // already closed
        }
    }
}
