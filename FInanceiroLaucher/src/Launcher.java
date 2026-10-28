import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.List;
import java.util.zip.*;
/**
 * 
 * @author RodolfoFonseca
 */
public class Launcher extends JFrame{
     private static final String SERVER =
        "https://seudominio.com/atualizador/latest.properties";

    private static final String APP_NAME = "MeuSistema.jar";

    private final Path root;
    private final Path appDir;
    private final Path configDir;
    private final Path historyFile;

    private final JLabel status = new JLabel("Verificando atualizações...");
    private final JLabel percent = new JLabel("0%");
    private final JLabel image = new JLabel("", SwingConstants.CENTER);
    private final JLabel version = new JLabel("Versão instalada: desconhecida");
    private final JProgressBar progress = new JProgressBar(0, 100);
    private final JTextArea changes = new JTextArea();
    private final JButton action = new JButton("Aguarde...");
    private final JButton history = new JButton("Histórico");
    private final JPanel content = new JPanel(new BorderLayout(12, 12));

    private Properties release;
    private String installedVersion = "0.0.0";
    private boolean busy = true;

    public Launcher() {
        root = Paths.get(System.getProperty("user.dir"))
                    .toAbsolutePath().normalize();
        appDir = root.resolve("app");
        configDir = root.resolve("config");
        historyFile = root.resolve("historico")
                          .resolve("atualizacoes.txt");

        buildWindow();
    }

    private void buildWindow() {
        setTitle("MeuSistema - Atualizador");
        setSize(720, 440);
        setMinimumSize(new Dimension(600, 380));
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        content.setBorder(new EmptyBorder(18, 18, 18, 18));
        content.setBackground(Color.WHITE);

        image.setPreferredSize(new Dimension(240, 200));
        image.setText("Preparando atualização...");
        content.add(image, BorderLayout.WEST);

        JPanel right = new JPanel(new BorderLayout(8, 8));
        right.setOpaque(false);

        JLabel heading = new JLabel("Atualizações do sistema");
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 18f));
        right.add(heading, BorderLayout.NORTH);

        changes.setEditable(false);
        changes.setLineWrap(true);
        changes.setWrapStyleWord(true);
        changes.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        changes.setText("Verificando se há uma nova versão...");
        right.add(new JScrollPane(changes), BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(8, 8));
        bottom.setOpaque(false);

        JPanel progressPanel = new JPanel(new BorderLayout(8, 4));
        progressPanel.setOpaque(false);
        progressPanel.add(status, BorderLayout.NORTH);

        JPanel barPanel = new JPanel(new BorderLayout(8, 0));
        barPanel.setOpaque(false);
        barPanel.add(progress, BorderLayout.CENTER);
        barPanel.add(percent, BorderLayout.EAST);

        progress.setStringPainted(false);
        progressPanel.add(barPanel, BorderLayout.CENTER);
        bottom.add(progressPanel, BorderLayout.NORTH);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.setOpaque(false);
        buttons.add(history);
        buttons.add(action);
        bottom.add(buttons, BorderLayout.SOUTH);

        right.add(bottom, BorderLayout.SOUTH);
        content.add(right, BorderLayout.CENTER);
        add(content);

        action.setEnabled(false);

        action.addActionListener(e -> {
            if (!busy) {
                launchApplication();
            }
        });

        history.addActionListener(e -> showHistory());

        setVisible(true);

        new UpdateWorker().execute();
    }

    private class UpdateWorker extends SwingWorker<Void, Integer> {

        private String newVersion;
        private Path downloadedZip;
        private Path stageDir;
        private String resultMessage;
        private boolean updateAvailable;

        @Override
        protected Void doInBackground() throws Exception {
            Files.createDirectories(configDir);
            Files.createDirectories(historyFile.getParent());
            Files.createDirectories(root.resolve("backup"));

            installedVersion = readInstalledVersion();

            Properties remote = new Properties();

            URL url = new URL(SERVER);
            URLConnection connection = url.openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);

            try (InputStream in = connection.getInputStream()) {
                remote.load(new InputStreamReader(
                    in, StandardCharsets.UTF_8));
            }

            release = remote;
            newVersion = required(remote, "version");

            String packageUrl = required(remote, "package.url");
            String expectedHash = required(remote, "package.sha256")
                .trim().toLowerCase(Locale.ROOT);

            if (!expectedHash.matches("[0-9a-f]{64}")) {
                throw new IOException(
                    "SHA-256 do pacote não foi configurado corretamente.");
            }

            if (compareVersions(newVersion, installedVersion) <= 0) {
                updateAvailable = false;
                resultMessage = "Seu sistema já está atualizado.";
                return null;
            }

            updateAvailable = true;

            SwingUtilities.invokeLater(() -> {
                version.setText("Nova versão: " + newVersion);
                status.setText("Preparando download...");
                action.setText("Atualizando...");
            });

            loadReleaseImage(remote.getProperty("image.url", ""));

            URL packageURL = new URL(packageUrl);
            URLConnection packageConnection = packageURL.openConnection();
            packageConnection.setConnectTimeout(15000);
            packageConnection.setReadTimeout(30000);

            long total = packageConnection.getContentLengthLong();

            downloadedZip = Files.createTempFile("meusistema-", ".zip");

            try (InputStream in = packageConnection.getInputStream();
                 OutputStream out = Files.newOutputStream(downloadedZip)) {

                byte[] buffer = new byte[8192];
                long downloaded = 0;
                int count;

                while ((count = in.read(buffer)) != -1) {
                    if (isCancelled()) {
                        throw new IOException("Atualização cancelada.");
                    }

                    out.write(buffer, 0, count);
                    downloaded += count;

                    if (total > 0) {
                        publish((int) Math.min(
                            99, downloaded * 100 / total));
                    }
                }
            }

            publish(99);

            String actualHash = sha256(downloadedZip);

            if (!actualHash.equals(expectedHash)) {
                throw new IOException(
                    "O pacote baixado não passou na validação SHA-256.");
            }

            Path staging = root.resolve("staging");
            deleteDirectory(staging);
            Files.createDirectories(staging);

            stageDir = staging;

            extractZip(downloadedZip, staging);

            Path stagedApp = staging.resolve("app");

            if (!Files.isDirectory(stagedApp)
                    || !Files.isRegularFile(stagedApp.resolve(APP_NAME))) {
                throw new IOException(
                    "O ZIP não contém app/" + APP_NAME);
            }

            Path currentApp = root.resolve("app");
            Path backupApp = root.resolve("backup").resolve(
                "app-" + installedVersion + "-" + System.currentTimeMillis());

            if (Files.exists(currentApp)) {
                movePath(currentApp, backupApp);
            }

            try {
                movePath(stagedApp, currentApp);
            } catch (Exception ex) {
                if (Files.exists(backupApp)
                        && !Files.exists(currentApp)) {
                    movePath(backupApp, currentApp);
                }
                throw ex;
            }

            Files.write(configDir.resolve("versao.txt"),
                newVersion.getBytes(StandardCharsets.UTF_8));

            appendHistory(
                "Versão " + newVersion + " - "
                + release.getProperty("release.date", "")
                + System.lineSeparator()
                + release.getProperty("changes", "")
                    .replace("|", System.lineSeparator())
                + System.lineSeparator()
            );

            publish(100);
            resultMessage = "Atualização concluída com sucesso.";
            return null;
        }

        @Override
        protected void process(List<Integer> values) {
            int value = values.get(values.size() - 1);
            progress.setValue(value);
            percent.setText(value + "%");
            status.setText(value < 100
                ? "Baixando e instalando atualização..."
                : "Atualização concluída.");
        }

        @Override
        protected void done() {
            busy = false;
            action.setEnabled(true);

            try {
                get();

                if (updateAvailable) {
                    status.setText(resultMessage);
                    changes.setText(
                        release.getProperty("changes", "")
                            .replace("|", "\n"));
                    action.setText("Abrir sistema");
                } else {
                    status.setText(resultMessage);
                    version.setText(
                        "Versão instalada: " + installedVersion);
                    changes.setText(
                        "Nenhuma atualização disponível.");
                    action.setText("Abrir sistema");
                }
            } catch (Exception ex) {
                status.setText("Não foi possível atualizar.");
                changes.setText(rootMessage(ex));
                action.setText("Tentar novamente");
                action.removeActionListener(action.getActionListeners()[0]);
                action.addActionListener(e -> {
                    dispose();
                    new Launcher();
                });
            } finally {
                if (downloadedZip != null) {
                    try {
                        Files.deleteIfExists(downloadedZip);
                    } catch (IOException ignored) {}
                }

                if (stageDir != null) {
                    try {
                        deleteDirectory(stageDir);
                    } catch (IOException ignored) {}
                }
            }
        }
    }

    private void loadReleaseImage(String imageUrl) {
        if (imageUrl == null || imageUrl.trim().isEmpty()) {
            return;
        }

        SwingUtilities.invokeLater(() -> {
            try {
                URLConnection c = new URL(imageUrl).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);

                try (InputStream in = c.getInputStream()) {
                    ImageIcon icon = new ImageIcon(
                        readAllBytes(in));

                    Image scaled = icon.getImage().getScaledInstance(
                        240, 200, Image.SCALE_SMOOTH);

                    image.setIcon(new ImageIcon(scaled));
                    image.setText("");
                }
            } catch (Exception ignored) {
                image.setText("Novidades da versão");
            }
        });
    }

    private String readInstalledVersion() throws IOException {
        Path file = configDir.resolve("versao.txt");

        if (!Files.exists(file)) {
            return "0.0.0";
        }

        return new String(Files.readAllBytes(file),
            StandardCharsets.UTF_8).trim();
    }

    private static int compareVersions(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");

        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int n1 = i < x.length ? parseNumber(x[i]) : 0;
            int n2 = i < y.length ? parseNumber(y[i]) : 0;

            if (n1 != n2) {
                return Integer.compare(n1, n2);
            }
        }

        return 0;
    }

    private static int parseNumber(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static String required(Properties p, String key)
            throws IOException {
        String value = p.getProperty(key);

        if (value == null || value.trim().isEmpty()) {
            throw new IOException(
                "Propriedade obrigatória ausente: " + key);
        }

        return value.trim();
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");

        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;

            while ((count = in.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }

        StringBuilder result = new StringBuilder();

        for (byte b : digest.digest()) {
            result.append(String.format("%02x", b & 0xff));
        }

        return result.toString();
    }

    private static void extractZip(Path zip, Path destination)
            throws IOException {

        Path safeRoot = destination.toAbsolutePath().normalize();

        try (ZipInputStream in = new ZipInputStream(
                Files.newInputStream(zip))) {

            ZipEntry entry;

            while ((entry = in.getNextEntry()) != null) {
                Path output = safeRoot.resolve(entry.getName())
                                     .normalize();

                if (!output.startsWith(safeRoot)) {
                    throw new IOException(
                        "Entrada ZIP inválida: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                } else {
                    Files.createDirectories(output.getParent());

                    try (OutputStream out =
                            Files.newOutputStream(output)) {
                        byte[] buffer = new byte[8192];
                        int count;

                        while ((count = in.read(buffer)) != -1) {
                            out.write(buffer, 0, count);
                        }
                    }
                }

                in.closeEntry();
            }
        }
    }

    private static void movePath(Path from, Path to)
            throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(from, to);
        }
    }

    private static void deleteDirectory(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }

        try (java.util.stream.Stream<Path> paths =
                Files.walk(path)) {
            Path[] items = paths.sorted(Comparator.reverseOrder())
                                .toArray(Path[]::new);

            for (Path item : items) {
                Files.deleteIfExists(item);
            }
        }
    }

    private void appendHistory(String text) throws IOException {
        Files.createDirectories(historyFile.getParent());

        Files.write(historyFile, text.getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private void showHistory() {
        try {
            String text = Files.exists(historyFile)
                ? new String(Files.readAllBytes(historyFile),
                    StandardCharsets.UTF_8)
                : "Nenhuma atualização registrada.";

            JTextArea area = new JTextArea(text, 18, 48);
            area.setEditable(false);
            area.setCaretPosition(0);

            JOptionPane.showMessageDialog(this,
                new JScrollPane(area),
                "Histórico de atualizações",
                JOptionPane.INFORMATION_MESSAGE);

        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this,
                "Erro ao ler o histórico: " + ex.getMessage());
        }
    }

    private void launchApplication() {
        Path jar = appDir.resolve(APP_NAME);

        if (!Files.isRegularFile(jar)) {
            JOptionPane.showMessageDialog(this,
                "O arquivo do sistema não foi encontrado:\n" + jar,
                "Erro", JOptionPane.ERROR_MESSAGE);
            return;
        }

        try {
            new ProcessBuilder(
                javaExecutable(), "-jar", jar.toString())
                .directory(appDir.toFile())
                .start();

            dispose();

        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this,
                "Não foi possível abrir o sistema:\n"
                + ex.getMessage(),
                "Erro", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static String javaExecutable() {
        String bin = System.getProperty("os.name")
            .toLowerCase(Locale.ROOT).contains("win")
            ? "java.exe" : "java";

        return Paths.get(System.getProperty("java.home"),
                         "bin", bin).toString();
    }

    private static String rootMessage(Throwable ex) {
        Throwable cause = ex;

        while (cause.getCause() != null) {
            cause = cause.getCause();
        }

        return cause.getMessage() == null
            ? cause.toString() : cause.getMessage();
    }

    private static byte[] readAllBytes(InputStream in)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;

        while ((count = in.read(buffer)) != -1) {
            out.write(buffer, 0, count);
        }

        return out.toByteArray();
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(Launcher::new);
    }
}
