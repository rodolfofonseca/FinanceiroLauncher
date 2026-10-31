
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
 * Sistema Gerenciador de atualizações de sistema
 *
 * @author RodolfoFonseca
 */
public class Launcher extends JFrame {

    private static final String SERVER = "https://financeirodev.api.hammertail.app/atualizador/releases.properties";
    private static final String APP_NAME = "FinanceiroDesktop.jar";
    private final Path root;
    private final Path appDir;
    private final Path configDir;
    private final Path historyFile;
    private final Path imageFile;
    private final Path launcherFile;
    private final Path newLauncherFile;
    private JFTelaAtualizador tela;
    private JLabel status;
    private JLabel percent;
    private JLabel image;
    private JLabel version;
    private JProgressBar progress;
    private JTextArea changes;
    private JButton action;
    private JButton history;
    private Properties release;
    private String installedVersion = "0.0.0";
    private String installedLauncherVersion;
    private String newLauncherVersion;
    private static final String LAUNCHER_VERSION = "0.0.0";
    private boolean busy = true;
    private boolean launcherUpdateAvailable = false;

    /**
     * Construtor da classe Launcher de atualização
     */
    public Launcher() {
        root = getInstallRoot();
        appDir = root.resolve("app");
        configDir = root.resolve("config");
        historyFile = root.resolve("historico").resolve("atualizacoes.txt");
        imageFile = configDir.resolve("imagem-atualizacao.png");

        try {
            launcherFile = Paths.get(Launcher.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
            newLauncherFile = root.resolve("LauncherNovo.jar");
        } catch (Exception ex) {
            throw new IllegalStateException("Não foi possível localizar o Launcher.", ex);
        }
        buildWindow();
    }

    /**
     * Construtor da janela
     */
    private void buildWindow() {
        tela = new JFTelaAtualizador();

        status = tela.status;
        percent = tela.percent;
        image = tela.image;
        version = tela.version;
        progress = tela.progress;
        changes = tela.changes;
        action = tela.action;
        history = tela.history;

        loadInstalledImage();

        action.setText("Verificando atualizações...");
        action.setEnabled(false);

        action.addActionListener(e -> {
            if (!busy) {
                launchApplication();
            }
        });

        history.addActionListener(e -> showHistory());

        tela.setLocationRelativeTo(null);
        tela.setVisible(true);

        new UpdateWorker().execute();
    }

    /**
     * Trabalho de atualização
     */
    private class UpdateWorker extends SwingWorker<Void, Integer> {

        private String newVersion;
        private Path downloadedZip;
        private Path stageDir;
        private String resultMessage;
        private boolean updateAvailable;
        private Path downloadedImage;

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
                remote.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            }

            release = remote;

            if (verificarAtualizacaoLauncher(remote)) {
                launcherUpdateAvailable = true;
                resultMessage = "Nova versão do Launcher disponível.";

                return null;
            }

            newVersion = required(remote, "version");

            String packageUrl = required(remote, "packageUrl");
            String expectedHash = required(remote, "sha256").trim().toLowerCase(Locale.ROOT);

            if (!expectedHash.matches("[0-9a-f]{64}")) {
                throw new IOException("SHA-256 do pacote não foi configurado corretamente.");
            }

            if (compareVersions(newVersion, installedVersion) <= 0) {
                updateAvailable = false;
                resultMessage = "Seu sistema já está atualizado.";
                return null;
            }

            updateAvailable = true;
            downloadedImage = downloadUpdateImage(remote.getProperty("image", ""));

            SwingUtilities.invokeLater(() -> {
                version.setText("Nova versão: " + newVersion);
                status.setText("Preparando download...");
                action.setText("Atualizando...");
            });

            loadReleaseImage(remote.getProperty("image", ""));

            URL packageURL = new URL(packageUrl);
            URLConnection packageConnection = packageURL.openConnection();
            packageConnection.setConnectTimeout(15000);
            packageConnection.setReadTimeout(30000);

            long total = packageConnection.getContentLengthLong();

            downloadedZip = Files.createTempFile("financeiroDesktop-", ".zip");

            try (InputStream in = packageConnection.getInputStream(); OutputStream out = Files.newOutputStream(downloadedZip)) {
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
                throw new IOException("O pacote baixado não passou na validação SHA-256.");
            }

            Path staging = root.resolve("staging");
            deleteDirectory(staging);
            Files.createDirectories(staging);

            stageDir = staging;

            extractZip(downloadedZip, staging);
            System.out.println("\n========== CONTEÚDO DO ZIP ==========");

            try (java.util.stream.Stream<Path> arquivos = Files.walk(staging)) {
                arquivos.forEach(arquivo -> {
                    System.out.println("Encontrado: " + staging.relativize(arquivo));
                });

            }

            System.out.println("=====================================\n");

            Path stagedApp = staging.resolve("app");

            if (!Files.isDirectory(stagedApp)) {
                try (java.util.stream.Stream<Path> caminhos = Files.walk(staging)) {
                    stagedApp = caminhos.filter(Files::isDirectory).filter(p -> p.getFileName() != null && p.getFileName().toString().equals("app")).filter(p -> Files.isRegularFile(p.resolve(APP_NAME))).findFirst().orElse(null);
                }
            }

            if (stagedApp == null || !Files.isRegularFile(stagedApp.resolve(APP_NAME))) {
                throw new IOException("Não foi encontrado " + APP_NAME + " dentro do ZIP.");
            }

            System.out.println("Pasta do sistema encontrada: " + stagedApp);
            System.out.println("JAR encontrado: " + stagedApp.resolve(APP_NAME));

            Path currentApp = root.resolve("app");
            Path backupApp = root.resolve("backup").resolve("app-" + installedVersion + "-" + System.currentTimeMillis());

            if (Files.exists(currentApp)) {
                movePath(currentApp, backupApp);
            }

            try {
                movePath(stagedApp, currentApp);
                if (downloadedImage != null) {
                    try {
                        Files.move(downloadedImage, imageFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                        downloadedImage = null;
                    } catch (AtomicMoveNotSupportedException ex) {
                        Files.move(downloadedImage, imageFile, StandardCopyOption.REPLACE_EXISTING);
                        downloadedImage = null;
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }
            } catch (Exception ex) {
                if (Files.exists(backupApp) && !Files.exists(currentApp)) {
                    movePath(backupApp, currentApp);
                }
                throw ex;
            }

            Files.write(configDir.resolve("versao.txt"), newVersion.getBytes(StandardCharsets.UTF_8));
            appendHistory("Versão " + newVersion + " - " + release.getProperty("release.date", "") + System.lineSeparator() + release.getProperty("changes", "").replace("|", System.lineSeparator()) + System.lineSeparator());
            publish(100);
            resultMessage = "Atualização concluída com sucesso.";
            return null;
        }

        @Override
        protected void process(List<Integer> values) {
            int value = values.get(values.size() - 1);
            progress.setValue(value);
            percent.setText(value + "%");
            status.setText(value < 100 ? "Baixando e instalando atualização..." : "Atualização concluída.");
        }

        @Override
        protected void done() {
            busy = false;
            action.setEnabled(true);

            try {
                get();
                
                if (launcherUpdateAvailable) {
                    status.setText("Atualizando o Launcher...");
                    version.setText("Nova versão do Launcher: " + newLauncherVersion);
                    changes.setText("Uma nova versão do atualizador foi baixada.\n O Launcher será reiniciado automaticamente.");
                    action.setEnabled(false);
                    
                    registrarHistoricoLauncher(release);

                    boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");

                    if (windows) {
                        prepararAtualizacaoWindows();
                    } else {
                        prepararAtualizacaoLinux();
                    }

                    tela.dispose();
                    System.exit(0);
                    return;
                }

                if (updateAvailable) {
                    loadInstalledImage();
                    status.setText(resultMessage);
                    changes.setText(release.getProperty("changes", "").replace("|", "\n"));
                    version.setText("Versão instalada: " + newVersion);
                } else {
                    status.setText(resultMessage);
                    version.setText("Versão instalada: " + installedVersion);
                    changes.setText("Nenhuma atualização disponível.");
                }

                action.setText("Abrir sistema");

            } catch (Exception ex) {
                Throwable causa = ex;
                if (ex instanceof java.util.concurrent.ExecutionException && ex.getCause() != null) {
                    causa = ex.getCause();
                }

                causa.printStackTrace();
                String mensagem = causa.getMessage();

                if (mensagem == null || mensagem.trim().isEmpty()) {
                    mensagem = causa.getClass().getName();
                }

                status.setText("Não foi possível atualizar.");
                changes.setText(mensagem);

                Path jar = appDir.resolve(APP_NAME);

                if (Files.isRegularFile(jar)) {
                    action.setText("Abrir sistema");
                } else {
                    action.setText("Tentar novamente");
                }

                System.err.println("ERRO NA ATUALIZAÇÃO: " + mensagem);

                for (java.awt.event.ActionListener listener : action.getActionListeners()) {
                    action.removeActionListener(listener);
                }

                action.addActionListener(e -> {
                    if (Files.isRegularFile(appDir.resolve(APP_NAME))) {
                        launchApplication();
                    } else {
                        tela.dispose();
                        new Launcher();
                    }
                });

            } finally {
                if (downloadedZip != null) {
                    try {
                        Files.deleteIfExists(downloadedZip);
                    } catch (IOException ignored) {
                    }
                }

                if (stageDir != null) {
                    try {
                        deleteDirectory(stageDir);
                    } catch (IOException ignored) {
                    }
                }

                if (downloadedImage != null) {
                    try {
                        Files.deleteIfExists(downloadedImage);
                    } catch (IOException ignored) {
                    }
                }
            }
        }
    }

    /**
     * Carrega a imagem da atualização
     *
     * @param imageUrl
     */
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
                    ImageIcon icon = new ImageIcon(readAllBytes(in));

                    Image scaled = icon.getImage().getScaledInstance(240, 200, Image.SCALE_SMOOTH);
                    image.setIcon(new ImageIcon(scaled));
                    image.setText("");
                }
            } catch (Exception ignored) {
                image.setText("Novidades da versão");
            }
        });
    }

    /**
     * Le a versão que está instalada
     *
     * @return
     * @throws IOException
     */
    private String readInstalledVersion() throws IOException {
        Path file = configDir.resolve("versao.txt");

        if (!Files.exists(file)) {
            return "0.0.0";
        }

        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
    }

    /**
     * Compara as versões
     *
     * @param a
     * @param b
     * @return
     */
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

    /**
     * Converte os números
     *
     * @param value
     * @return
     */
    private static int parseNumber(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /**
     * Verifica se não está faltando nenhuma propriedade obrigatória.
     *
     * @param p
     * @param key
     * @return
     * @throws IOException
     */
    private static String required(Properties p, String key) throws IOException {
        String value = p.getProperty(key);

        if (value == null || value.trim().isEmpty()) {
            throw new IOException("Propriedade obrigatória ausente: " + key);
        }

        return value.trim();
    }

    /**
     * Verifica o sha256 do servidor é o mesmo do arquivo baixado
     *
     * @param file
     * @return
     * @throws Exception
     */
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

    /**
     * Extrai o zip que foi baixado
     *
     * @param zip
     * @param destination
     * @throws IOException
     */
    private static void extractZip(Path zip, Path destination) throws IOException {
        Path safeRoot = destination.toAbsolutePath().normalize();

        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;

            while ((entry = in.getNextEntry()) != null) {
                Path output = safeRoot.resolve(entry.getName()).normalize();

                if (!output.startsWith(safeRoot)) {
                    throw new IOException("Entrada ZIP inválida: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                } else {
                    Files.createDirectories(output.getParent());

                    try (OutputStream out = Files.newOutputStream(output)) {
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

    /**
     * Move o arquivo
     *
     * @param from
     * @param to
     * @throws IOException
     */
    private static void movePath(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(from, to);
        }
    }

    /**
     * Deleta as pastas
     *
     * @param path
     * @throws IOException
     */
    private static void deleteDirectory(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }

        try (java.util.stream.Stream<Path> paths = Files.walk(path)) {
            Path[] items = paths.sorted(Comparator.reverseOrder()).toArray(Path[]::new);

            for (Path item : items) {
                Files.deleteIfExists(item);
            }
        }
    }

    /**
     * Registra o histórico do launcher
     *
     * @param release
     */
    private void registrarHistoricoLauncher(Properties release) throws IOException {

        String versao = release.getProperty("launcherVersion", "");
        String data = release.getProperty("launcher.release.date", "");
        String alteracoes = release.getProperty("launcher.changes", "");

        StringBuilder historico = new StringBuilder();

        historico.append("\n====================================\n");
        historico.append("ATUALIZAÇÃO DO LAUNCHER\n");
        historico.append("====================================\n");

        historico.append("Versão ").append(versao);

        if (!data.isEmpty()) {
            historico.append(" - ").append(data);
        }

        historico.append("\n\n");

        if (!alteracoes.isEmpty()) {
            historico.append(alteracoes.replace("|", "\n"));
        }

        historico.append("\n");

        appendHistory(historico.toString());
    }

    /**
     * Adiciona o histórico
     *
     * @param text
     * @throws IOException
     */
    private void appendHistory(String text) throws IOException {
        Files.createDirectories(historyFile.getParent());

        Files.write(historyFile, text.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /**
     * Mostra o histórico no formulário
     */
    private void showHistory() {
        try {
            String text = Files.exists(historyFile) ? new String(Files.readAllBytes(historyFile), StandardCharsets.UTF_8) : "Nenhuma atualização registrada.";

            JTextArea area = new JTextArea(text, 18, 48);
            area.setEditable(false);
            area.setCaretPosition(0);

            JOptionPane.showMessageDialog(tela, new JScrollPane(area), "Histórico de atualizações", JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Erro ao ler o histórico: " + ex.getMessage());
        }
    }

    /**
     * Abre a aplicação
     */
    private void launchApplication() {
        Path jar = appDir.resolve(APP_NAME);

        if (!Files.isRegularFile(jar)) {
            JOptionPane.showMessageDialog(tela, "O arquivo do sistema não foi encontrado:\n" + jar, "Erro", JOptionPane.ERROR_MESSAGE);
            return;
        }

        try {
            new ProcessBuilder(javaExecutable(), "-jar", jar.toString()).directory(appDir.toFile()).start();
            tela.dispose();
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Não foi possível abrir o sistema:\n" + ex.getMessage(), "Erro", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Executa os arquivos java no windows
     *
     * @return
     */
    private static String javaExecutable() {
        String bin = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
        return Paths.get(System.getProperty("java.home"), "bin", bin).toString();
    }

    /**
     * Verifica a mensagem de root
     *
     * @param ex
     * @return
     */
    private static String rootMessage(Throwable ex) {
        Throwable cause = ex;

        while (cause.getCause() != null) {
            cause = cause.getCause();
        }

        return cause.getMessage() == null ? cause.toString() : cause.getMessage();
    }

    /**
     * Le todos os bytes do arquivo
     *
     * @param in
     * @return
     * @throws IOException
     */
    private static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;

        while ((count = in.read(buffer)) != -1) {
            out.write(buffer, 0, count);
        }

        return out.toByteArray();
    }

    /**
     * Método main
     *
     * @param args
     */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(Launcher::new);
    }

    /**
     * Pega a instalação do sistema
     *
     * @return
     */
    private static Path getInstallRoot() {
        try {
            Path jar = Paths.get(Launcher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Files.isDirectory(jar) ? jar.toAbsolutePath().normalize() : jar.toAbsolutePath().normalize().getParent();
        } catch (Exception e) {
            throw new IllegalStateException("Não foi possível localizar o Launcher.", e);
        }
    }

    /**
     * Le a imagem que está instalada
     */
    private void loadInstalledImage() {
        if (!Files.exists(imageFile)) {
            image.setIcon(null);
            image.setText("Financeiro Desktop");
            return;
        }

        try {
            ImageIcon icon = new ImageIcon(imageFile.toString());

            Image scaled = icon.getImage().getScaledInstance(240, 200, Image.SCALE_SMOOTH);

            image.setIcon(new ImageIcon(scaled));
            image.setText("");

        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    /**
     * Download da imagem de atualização do sistema.
     *
     * @param imageUrl
     * @return
     */
    private Path downloadUpdateImage(String imageUrl) {
        if (imageUrl == null || imageUrl.trim().isEmpty()) {
            return null;
        }

        Path tempImage = null;

        try {
            URLConnection connection = new URL(imageUrl).openConnection();

            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);

            tempImage = Files.createTempFile(configDir, "imagem-nova-", ".png");

            try (InputStream in = connection.getInputStream()) {
                Files.copy(in, tempImage, StandardCopyOption.REPLACE_EXISTING);
            }

            ImageIcon icon = new ImageIcon(tempImage.toString());

            if (icon.getIconWidth() <= 0 || icon.getIconHeight() <= 0) {
                throw new IOException("Imagem da atualização inválida.");
            }

            return tempImage;
        } catch (Exception ex) {
            ex.printStackTrace();

            if (tempImage != null) {
                try {
                    Files.deleteIfExists(tempImage);
                } catch (IOException ignored) {
                }
            }

            return null;
        }
    }

    /**
     * Lê a versão do launcher que está instalada
     *
     * @return
     * @throws IOException
     */
    private String readInstalledLauncherVersion() throws IOException {
        Path file = configDir.resolve("versao-launcher.txt");

        if (!Files.exists(file)) {
            return LAUNCHER_VERSION;
        }

        String version = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim();
        return version.isEmpty() ? LAUNCHER_VERSION : version;
    }

    /**
     * Verifica a atualização do launcher
     *
     * @param remote
     * @return
     * @throws Exception
     */
    private boolean verificarAtualizacaoLauncher(Properties remote) throws Exception {
        installedLauncherVersion = readInstalledLauncherVersion();

        newLauncherVersion = remote.getProperty("launcherVersion", "").trim();

        if (newLauncherVersion.isEmpty()) {
            return false;
        }

        if (compareVersions(newLauncherVersion, installedLauncherVersion) <= 0) {
            return false;
        }

        String packageUrl = required(remote, "launcherPackageUrl");
        String expectedHash = required(remote, "launcherSha256").toLowerCase(Locale.ROOT);

        if (!expectedHash.matches("[0-9a-f]{64}")) {
            throw new IOException("SHA-256 do Launcher inválido.");
        }

        URLConnection connection = new URL(packageUrl).openConnection();

        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);

        Path temp = Files.createTempFile(root, "launcher-update-", ".jar");

        try {
            try (InputStream in = connection.getInputStream()) {
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            }

            String actualHash = sha256(temp);

            if (!actualHash.equals(expectedHash)) {
                throw new IOException("O novo Launcher não passou na validação SHA-256.");
            }

            try (java.util.jar.JarFile jar = new java.util.jar.JarFile(temp.toFile())) {
                java.util.jar.Manifest manifest = jar.getManifest();

                if (manifest == null || manifest.getMainAttributes().getValue("Main-Class") == null) {
                    throw new IOException("O novo Launcher não possui Main-Class no MANIFEST.");
                }
            }

            try {
                Files.move(temp, newLauncherFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temp, newLauncherFile, StandardCopyOption.REPLACE_EXISTING);
            }

            return true;

        } catch (Exception ex) {
            Files.deleteIfExists(temp);
            throw ex;
        }
    }

    /**
     * Pega o ID do processo atual
     *
     * @return
     */
    private static long getCurrentProcessId() {
        String processName = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
        int separator = processName.indexOf('@');

        if (separator > 0) {
            return Long.parseLong(processName.substring(0, separator));
        }

        throw new IllegalStateException("Não foi possível identificar o processo do Launcher.");
    }

    /**
     * Prepara a atualização do windows
     *
     * @throws IOException
     */
    private void prepararAtualizacaoWindows() throws IOException {
        Path script = root.resolve("atualizar-launcher.ps1");
        long pid = getCurrentProcessId();

        String target = launcherFile.toString();
        String novo = newLauncherFile.toString();
        String versao = newLauncherVersion;
        String java = Paths.get(System.getProperty("java.home"), "bin", "java.exe").toString();
        Path javaw = Paths.get(System.getProperty("java.home"), "bin", "javaw.exe");

        if (Files.exists(javaw)) {
            java = javaw.toString();
        }

        Path backup = root.resolve("Launcher-backup.jar");
        Path versionFile = configDir.resolve("versao-launcher.txt");

        String conteudo = "$ErrorActionPreference = 'Stop'\n $pidAntigo = " + pid + "\n $target = '" + psQuote(target) + "'\n $novo = '" + psQuote(novo) + "'\n $backup = '" + psQuote(backup.toString()) + "'\n $java = '" + psQuote(java) + "'\n $versao = '" + psQuote(versao) + "'\n $versionFile = '" + psQuote(versionFile.toString()) + "'\n while (Get-Process -Id $pidAntigo -ErrorAction SilentlyContinue) {\n Start-Sleep -Seconds 1\n }\n try {\n Copy-Item -LiteralPath $target -Destination $backup -Force\n Move-Item -LiteralPath $novo -Destination $target -Force\n Set-Content -LiteralPath $versionFile -Value $versao -Encoding UTF8\n Start-Process -FilePath $java -ArgumentList ('-jar \"' + $target + '\"') -WorkingDirectory (Split-Path $target)\n Remove-Item -LiteralPath $backup -Force -ErrorAction SilentlyContinue\n } catch {\n if (Test-Path -LiteralPath $backup) {\n Copy-Item -LiteralPath $backup -Destination $target -Force\n }\n exit 1\n }\n Remove-Item -LiteralPath $MyInvocation.MyCommand.Path -Force\n";
        Files.write(script, conteudo.getBytes(StandardCharsets.UTF_8));

        new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script.toString()).start();
    }

    /**
     * Tira os caracteres não reconhecidos
     *
     * @param value
     * @return
     */
    private static String psQuote(String value) {
        return value.replace("'", "''");
    }

    /**
     * Prepara a atualização no linux
     *
     * @throws IOException
     */
    private void prepararAtualizacaoLinux() throws IOException {
        Path script = root.resolve("atualizar-launcher.sh");
        long pid = getCurrentProcessId();

        String target = launcherFile.toString();
        String novo = newLauncherFile.toString();
        String versao = newLauncherVersion;
        String java = javaExecutable();

        Path backup = root.resolve("Launcher-backup.jar");
        Path versionFile = configDir.resolve("versao-launcher.txt");

        String conteudo = "#!/bin/sh\n PID=" + pid + "\n TARGET=" + shellQuote(target) + "\n NOVO=" + shellQuote(novo) + "\n BACKUP=" + shellQuote(backup.toString()) + "\n JAVA=" + shellQuote(java) + "\n VERSAO=" + shellQuote(versao) + "\n VERSION_FILE=" + shellQuote(versionFile.toString()) + "\n \n while kill -0 \"$PID\" 2>/dev/null; do\n sleep 1\n done\n \n cp \"$TARGET\" \"$BACKUP\" || exit 1\n \n if ! mv -f \"$NOVO\" \"$TARGET\"; then\n cp \"$BACKUP\" \"$TARGET\"\n exit 1\n fi\n \n printf '%s\\n' \"$VERSAO\" > \"$VERSION_FILE\" || {\n cp \"$BACKUP\" \"$TARGET\"\n exit 1\n }\n \n \"$JAVA\" -jar \"$TARGET\" >/dev/null 2>&1 &\n \n if [ $? -eq 0 ]; then\n rm -f \"$BACKUP\"\n fi\n \n rm -f \"$0\"\n";

        Files.write(script, conteudo.getBytes(StandardCharsets.UTF_8));
        script.toFile().setExecutable(true);

        new ProcessBuilder("sh", script.toString()).start();
    }

    /**
     * Limpa caracteres linux
     *
     * @param value
     * @return
     */
    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
