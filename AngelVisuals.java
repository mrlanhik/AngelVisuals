import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class AngelVisuals extends JFrame {

    // ======================= CONFIG =======================
    static final Path CFG_FILE = Path.of("angelvisuals.properties");

    static class Config {
        String nickname = "Player";
        String mcPath = defaultMcPath();
        String version = "";
        int ramMb = 2048;

        static String defaultMcPath() {
            String os = System.getProperty("os.name").toLowerCase();
            String home = System.getProperty("user.home");
            if (os.contains("win")) return Path.of(System.getenv("APPDATA"), ".minecraft").toString();
            if (os.contains("mac")) return Path.of(home, "Library", "Application Support", "minecraft").toString();
            return Path.of(home, ".minecraft").toString();
        }

        static Config load() {
            Config c = new Config();
            if (!Files.exists(CFG_FILE)) return c;
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(CFG_FILE)) { p.load(in); }
            catch (IOException ignored) { return c; }
            c.nickname = p.getProperty("nickname", c.nickname);
            c.mcPath = p.getProperty("mcPath", c.mcPath);
            c.version = p.getProperty("version", c.version);
            try { c.ramMb = Integer.parseInt(p.getProperty("ramMb", "2048")); } catch (Exception ignored) {}
            return c;
        }

        void save() {
            Properties p = new Properties();
            p.setProperty("nickname", nickname);
            p.setProperty("mcPath", mcPath);
            p.setProperty("version", version);
            p.setProperty("ramMb", String.valueOf(ramMb));
            try (OutputStream out = Files.newOutputStream(CFG_FILE)) {
                p.store(out, "Angel Visuals config");
            } catch (IOException ignored) {}
        }
    }

    // ======================= MINI JSON =======================
    static class Json {
        static Object parse(String s) {
            Parser p = new Parser(s);
            Object v = p.parseValue();
            p.skipWs();
            return v;
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> obj(Object o) { return (Map<String, Object>) o; }
        @SuppressWarnings("unchecked")
        static List<Object> arr(Object o) { return (List<Object>) o; }

        static String str(Map<String, Object> m, String k, String def) {
            Object v = m.get(k); return v == null ? def : v.toString();
        }

        @SuppressWarnings("unchecked")
        static Map<String, Object> path(Map<String, Object> m, String... keys) {
            Object cur = m;
            for (String k : keys) {
                if (!(cur instanceof Map)) return Map.of();
                Object next = ((Map<String, Object>) cur).get(k);
                if (next == null) return Map.of();
                cur = next;
            }
            return cur instanceof Map ? (Map<String, Object>) cur : Map.of();
        }

        static class Parser {
            final String s; int i = 0;
            Parser(String s) { this.s = s; }
            void skipWs() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
            Object parseValue() {
                skipWs();
                if (i >= s.length()) return null;
                char c = s.charAt(i);
                if (c == '{') return parseObject();
                if (c == '[') return parseArray();
                if (c == '"') return parseString();
                if (c == 't') { i += 4; return Boolean.TRUE; }
                if (c == 'f') { i += 5; return Boolean.FALSE; }
                if (c == 'n') { i += 4; return null; }
                return parseNumber();
            }
            Map<String, Object> parseObject() {
                Map<String, Object> m = new LinkedHashMap<>();
                i++; skipWs();
                if (s.charAt(i) == '}') { i++; return m; }
                while (true) {
                    skipWs(); String k = parseString(); skipWs(); i++; // ':'
                    Object v = parseValue(); m.put(k, v); skipWs();
                    if (s.charAt(i) == ',') { i++; continue; }
                    i++; break;
                }
                return m;
            }
            List<Object> parseArray() {
                List<Object> l = new ArrayList<>();
                i++; skipWs();
                if (s.charAt(i) == ']') { i++; return l; }
                while (true) {
                    l.add(parseValue()); skipWs();
                    if (s.charAt(i) == ',') { i++; continue; }
                    i++; break;
                }
                return l;
            }
            String parseString() {
                StringBuilder b = new StringBuilder();
                i++;
                while (i < s.length()) {
                    char c = s.charAt(i++);
                    if (c == '"') break;
                    if (c == '\\') {
                        char e = s.charAt(i++);
                        switch (e) {
                            case 'n' -> b.append('\n');
                            case 't' -> b.append('\t');
                            case 'r' -> b.append('\r');
                            case '"' -> b.append('"');
                            case '\\' -> b.append('\\');
                            case '/' -> b.append('/');
                            case 'u' -> { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                            default -> b.append(e);
                        }
                    } else b.append(c);
                }
                return b.toString();
            }
            Object parseNumber() {
                int start = i;
                while (i < s.length() && "-+0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
                String num = s.substring(start, i);
                if (num.contains(".") || num.contains("e") || num.contains("E"))
                    return Double.parseDouble(num);
                return Long.parseLong(num);
            }
        }
    }

    // ======================= DOWNLOADER =======================
    static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS).build();

    static void downloadIfMissing(URI uri, Path target) throws Exception {
        if (Files.exists(target) && Files.size(target) > 0) return;
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".part");
        HttpResponse<Path> resp = HTTP.send(
                HttpRequest.newBuilder(uri).build(),
                HttpResponse.BodyHandlers.ofFile(tmp));
        if (resp.statusCode() != 200)
            throw new IOException("HTTP " + resp.statusCode() + " for " + uri);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }

    // ======================= VERSION MANAGER =======================
    static List<String> installedVersions(String mcPath) {
        Path dir = Path.of(mcPath, "versions");
        if (!Files.exists(dir)) return List.of();
        try (var s = Files.list(dir)) {
            return s.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) { return List.of(); }
    }

    record LaunchSpec(String mainClass, List<Path> classpath,
                      Path nativesDir, Path assetsDir, String assetIndex) {}

    static LaunchSpec buildSpec(Config cfg) throws Exception {
        Path mc = Path.of(cfg.mcPath);
        Path vdir = mc.resolve("versions").resolve(cfg.version);
        Path jsonFile = vdir.resolve(cfg.version + ".json");
        if (!Files.exists(jsonFile))
            throw new IllegalStateException("Не найден " + jsonFile);

        Map<String, Object> root = Json.obj(Json.parse(Files.readString(jsonFile)));
        String mainClass = Json.str(root, "mainClass", "net.minecraft.client.main.Main");
        String assetIndex = root.containsKey("assetIndex")
                ? Json.str(Json.obj(root.get("assetIndex")), "id", cfg.version)
                : Json.str(root, "assets", cfg.version);

        Path libDir = mc.resolve("libraries");
        List<Path> classpath = new ArrayList<>();
        Path nativesDir = mc.resolve("natives").resolve(cfg.version);
        Files.createDirectories(nativesDir);

        for (Object libObj : Json.arr(root.get("libraries"))) {
            Map<String, Object> lib = Json.obj(libObj);
            if (lib.containsKey("rules") && !rulesAllow(Json.arr(lib.get("rules")))) continue;

            Map<String, Object> downloads = Json.path(lib, "downloads");
            Map<String, Object> artifact = Json.path(downloads, "artifact");
            if (artifact.containsKey("path")) {
                Path target = libDir.resolve(artifact.get("path").toString());
                downloadIfMissing(URI.create(artifact.get("url").toString()), target);
                classpath.add(target);
            }
            Map<String, Object> classifiers = Json.path(downloads, "classifiers");
            for (var e : classifiers.entrySet()) {
                String key = e.getKey();
                if (!(key.contains("natives-windows") || key.contains("natives-linux") || key.contains("natives-macos")))
                    continue;
                Map<String, Object> nat = Json.obj(e.getValue());
                Path target = libDir.resolve(nat.get("path").toString());
                downloadIfMissing(URI.create(nat.get("url").toString()), target);
                extractNatives(target, nativesDir);
            }
        }

        Path clientJar = vdir.resolve(cfg.version + ".jar");
        Map<String, Object> client = Json.path(root, "downloads", "client");
        if (client.containsKey("url"))
            downloadIfMissing(URI.create(client.get("url").toString()), clientJar);
        classpath.add(clientJar);

        Path assetsDir = mc.resolve("assets");
        Map<String, Object> ai = Json.path(root, "assetIndex");
        if (ai.containsKey("url")) {
            Path idx = assetsDir.resolve("indexes").resolve(assetIndex + ".json");
            downloadIfMissing(URI.create(ai.get("url").toString()), idx);
        }

        return new LaunchSpec(mainClass, classpath, nativesDir, assetsDir, assetIndex);
    }

    static boolean rulesAllow(List<Object> rules) {
        boolean allow = false;
        String os = osName();
        for (Object r : rules) {
            Map<String, Object> rule = Json.obj(r);
            String action = Json.str(rule, "action", "");
            Map<String, Object> osNode = Json.path(rule, "os");
            boolean matches = osNode.isEmpty() || os.equals(Json.str(osNode, "name", ""));
            if (matches) allow = "allow".equals(action);
        }
        return allow;
    }

    static String osName() {
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) return "windows";
        if (os.contains("mac")) return "osx";
        return "linux";
    }

    static void extractNatives(Path jar, Path dest) throws IOException {
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(jar))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName();
                if (e.isDirectory() || name.endsWith(".class") || name.startsWith("META-INF")) continue;
                Path out = dest.resolve(name).normalize();
                if (!out.startsWith(dest)) continue;
                Files.createDirectories(out.getParent());
                Files.copy(zin, out, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    // ======================= GAME LAUNCHER =======================
    static Process launchGame(Config cfg) throws Exception {
        LaunchSpec spec = buildSpec(cfg);
        String cp = String.join(File.pathSeparator,
                spec.classpath().stream().map(Path::toString).toList());

        List<String> args = new ArrayList<>();
        args.add(resolveJava());
        args.add("-Xmx" + cfg.ramMb + "M");
        args.add("-Xms" + Math.max(512, cfg.ramMb / 2) + "M");
        args.add("-Djava.library.path=" + spec.nativesDir());
        args.add("-cp"); args.add(cp);
        args.add(spec.mainClass());
        args.add("--username");   args.add(cfg.nickname);
        args.add("--version");    args.add(cfg.version);
        args.add("--gameDir");    args.add(cfg.mcPath);
        args.add("--assetsDir");  args.add(spec.assetsDir().toString());
        args.add("--assetIndex"); args.add(spec.assetIndex());
        args.add("--accessToken");args.add("0");
        args.add("--userType");   args.add("legacy");
        args.add("--versionType");args.add("release");

        ProcessBuilder pb = new ProcessBuilder(args);
        pb.directory(Path.of(cfg.mcPath).toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        return pb.start();
    }

    static String resolveJava() {
        String os = System.getProperty("os.name").toLowerCase();
        String exe = os.contains("win") ? "javaw.exe" : "java";
        Path candidate = Path.of(System.getProperty("java.home"), "bin", exe);
        return Files.exists(candidate) ? candidate.toString() : exe;
    }

    // ======================= UI =======================
    private final Config cfg = Config.load();
    private final JTextField nickField = new JTextField(cfg.nickname, 22);
    private final JTextField pathField = new JTextField(cfg.mcPath, 22);
    private final JComboBox<String> versionBox = new JComboBox<>();
    private final JSpinner ramSpinner = new JSpinner(new SpinnerNumberModel(cfg.ramMb, 512, 16384, 512));
    private final JButton playBtn = new JButton("▶ Играть");
    private final JLabel status = new JLabel(" ");

    public AngelVisuals() {
        super("Angel Visuals");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(500, 380);
        setLocationRelativeTo(null);

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(new EmptyBorder(12, 12, 12, 12));
        GridBagConstraints g = new GridBagConstraints();
        g.insets = new Insets(6, 6, 6, 6);
        g.anchor = GridBagConstraints.WEST;

        JLabel title = new JLabel("Angel Visuals");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 20f));
        title.setForeground(new Color(90, 160, 255));
        title.setHorizontalAlignment(SwingConstants.CENTER);
        title.setBorder(new EmptyBorder(10, 0, 0, 0));

        addRow(form, g, 0, "Ник:", nickField);
        addRow(form, g, 1, ".minecraft:", pathField);
        addRow(form, g, 2, "Версия:", versionBox);
        addRow(form, g, 3, "RAM (MB):", ramSpinner);

        JButton refresh = new JButton("🔄 Обновить");
        playBtn.setBackground(new Color(76, 175, 80));
        playBtn.setForeground(Color.WHITE);
        playBtn.setOpaque(true);
        playBtn.setBorderPainted(false);

        JPanel buttons = new JPanel();
        buttons.add(refresh);
        buttons.add(playBtn);

        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(buttons, BorderLayout.NORTH);
        bottom.add(status, BorderLayout.SOUTH);

        setLayout(new BorderLayout());
        add(title, BorderLayout.NORTH);
        add(form, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);

        refresh.addActionListener(e -> reloadVersions());
        playBtn.addActionListener(e -> onPlay());

        reloadVersions();
    }

    private void addRow(JPanel p, GridBagConstraints g, int row, String label, JComponent field) {
        g.gridx = 0; g.gridy = row; g.weightx = 0; g.fill = GridBagConstraints.NONE;
        p.add(new JLabel(label), g);
        g.gridx = 1; g.weightx = 1; g.fill = GridBagConstraints.HORIZONTAL;
        p.add(field, g);
    }

    private void reloadVersions() {
        versionBox.removeAllItems();
        List<String> vers = installedVersions(pathField.getText().trim());
        for (String v : vers) versionBox.addItem(v);
        if (!vers.isEmpty()) {
            if (cfg.version != null && vers.contains(cfg.version)) versionBox.setSelectedItem(cfg.version);
            else versionBox.setSelectedIndex(vers.size() - 1);
        }
        status.setText("Найдено версий: " + vers.size());
    }

    private void onPlay() {
        cfg.nickname = nickField.getText().trim();
        cfg.mcPath = pathField.getText().trim();
        cfg.version = (String) versionBox.getSelectedItem();
        cfg.ramMb = (int) ramSpinner.getValue();
        cfg.save();

        if (cfg.nickname.isEmpty() || cfg.version == null) {
            JOptionPane.showMessageDialog(this, "Введи ник и выбери версию");
            return;
        }
        playBtn.setEnabled(false);
        status.setText("Подготовка файлов и запуск...");

        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() {
                try {
                    launchGame(cfg);
                    SwingUtilities.invokeLater(() -> status.setText("Игра запущена."));
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        status.setText("Ошибка: " + ex.getMessage());
                        JOptionPane.showMessageDialog(AngelVisuals.this,
                                "Ошибка:\n" + ex.getMessage(), "Ошибка",
                                JOptionPane.ERROR_MESSAGE);
                    });
                }
                return null;
            }
            @Override protected void done() { playBtn.setEnabled(true); }
        }.execute();
    }

    // ======================= MAIN =======================
    public static void main(String[] args) {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
        catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> new AngelVisuals().setVisible(true));
    }
}
