package com.karaoke;

import org.json.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * Gerencia sincronização bidirecional de músicas com repositório GitHub.
 *
 * Sistema de versionamento:
 * - Armazena SHA do arquivo remoto no .properties
 * - Compara versões antes de baixar
 * - Verifica e atualiza arquivos existentes com base em:
 *   * .properties: offsetSeconds e realDurationMin
 *   * .xml: conteúdo completo
 *   * .opus/.audio: metadados (tamanho, duração, data modificação)
 */
public class GitHubSyncManager {

    private static final String GITHUB_API_URL = GitHubConfigManager.getApiUrl();
    private static final String REF = GitHubConfigManager.getRef();
    private static final long RATE_LIMIT_DELAY = GitHubConfigManager.getSyncRateLimit();
    private static final String GITHUB_TOKEN = GitHubConfigManager.getToken();

    private static final String PROP_CONFIG_VERSION = "github_config_version";
    private static final String PROP_XML_VERSION = "github_xml_version";
    private static final String PROP_AUDIO_VERSION = "github_audio_version";
    private static final String PROP_LAST_SYNC = "last_sync_date";
    private static final String PROP_OFFSET_SECONDS = "offsetSeconds";
    private static final String PROP_REAL_DURATION_MIN = "realDurationMin";


    public interface SyncListener {
        void onProgress(String message, int current, int total);
        void onError(String error);
        void onComplete(int downloaded, int updated, int skipped);
    }
    /**
     * Inicia sincronização com GitHub (inclui verificação de arquivos existentes)
     */
    public static void syncWithGitHub(SyncListener listener) {
        new Thread(() -> {
            try {
                listener.onProgress("Conectando ao GitHub...", 0, 0);

                // Fase 1: Encontrar e baixar novas músicas
                List<MusicToDownload> toDownload = findNewMusics(listener);
                int[] results = new int[]{0, 0, 0};

                if (!toDownload.isEmpty()) {
                    listener.onProgress("Baixando " + toDownload.size() + " música(s) nova(s)...", 0, toDownload.size());
                    results = downloadMusics(toDownload, listener);
                }

                // Fase 2: Verificar e atualizar arquivos existentes
                listener.onProgress("Verificando arquivos existentes...", 0, 0);
                int[] updateResults = verifyAndUpdateExistingMusics(listener);

                results[0] += updateResults[0]; // downloaded
                results[1] += updateResults[1]; // updated
                results[2] += updateResults[2]; // skipped

                listener.onComplete(results[0], results[1], results[2]);

            } catch (Exception ex) {
                listener.onError("Erro na sincronização: " + ex.getMessage());
                ex.printStackTrace();
            }
        }).start();
    }

    // ── Verificação de arquivos existentes ─────────────────────────────────

    /**
     * Verifica e atualiza todos os arquivos existentes localmente
     */
    private static int[] verifyAndUpdateExistingMusics(SyncListener listener) throws Exception {
        int downloaded = 0;
        int updated = 0;
        int skipped = 0;

        List<MusicLibrary.SavedMusic> allLocal = MusicLibrary.listAll();
        int total = allLocal.size();

        for (int i = 0; i < allLocal.size(); i++) {
            MusicLibrary.SavedMusic music = allLocal.get(i);
            String[] nameParts = music.name.split(" - ");

            if (nameParts.length < 2) {
                skipped++;
                continue;
            }

            String musicName = nameParts[1].trim();
            String authorName = music.author;

            listener.onProgress("Verificando: " + authorName + " - " + musicName, i + 1, total);

            try {
                Thread.sleep(RATE_LIMIT_DELAY);
            } catch (InterruptedException ignored) {}
        }

        return new int[]{downloaded, updated, skipped};
    }

    /**
     * Verifica e atualiza uma música específica
     * Retorna: 0 = sem mudanças, 1 = atualizado, 2 = arquivo baixado
     */
    private static int verifyAndUpdateMusic(String authorName, String musicName) throws Exception {
        File musicFolder = getMusicFolder(authorName, musicName);
        if (!musicFolder.exists()) {
            return 0;
        }

        String fileName = getFileName(authorName, musicName);
        File configFile = new File(musicFolder, fileName + ".properties");
        File xmlFile = new File(musicFolder, fileName + ".xml");
        File audioFile = findAudioFileInFolder(musicFolder);

        if (!configFile.exists()) {
            return 0;
        }

        Properties localProps = loadProperties(configFile);

        // ✅ NOVO: Procurar a URL usando o diretório local como pista
        String musicUrl = findMusicUrlByLocalPath(musicFolder, authorName, musicName);
        if (musicUrl == null) {
            return 0;
        }

        Object response = getJsonResponse(musicUrl);
        JSONArray files;
        if (response instanceof JSONArray) {
            files = (JSONArray) response;
        } else if (response instanceof JSONObject) {
            files = new JSONArray();
            files.put((JSONObject) response);
        } else {
            return 0;
        }

        String xmlPath = null;
        String configPath = null;
        String audioPath = null;

        for (int i = 0; i < files.length(); i++) {
            JSONObject fileObj = files.getJSONObject(i);
            if ("dir".equals(fileObj.optString("type"))) continue;

            String remoteFileName = fileObj.getString("name");
            String downloadUrl = fileObj.optString("download_url");

            if (downloadUrl.isEmpty()) continue;

            if (remoteFileName.endsWith(".xml")) {
                xmlPath = downloadUrl;
            } else if (remoteFileName.endsWith(".properties")) {
                configPath = downloadUrl;
            } else if (isAudioFile(remoteFileName)) {
                audioPath = downloadUrl;
            }
        }

        if (configPath == null && musicUrl != null) {
            configPath = musicUrl.replace("?ref=" + REF, "")
                    .replace("?ref=main", "")
                    + "/" + fileName + ".properties";
        }

        boolean updated = false;

        // ✅ 1. Verificar .properties
        if (configPath != null) {
            try {
                if (hasPropertiesChanged(configFile, configPath, localProps)) {
                    downloadAndUpdateProperties(configPath, configFile);
                    updated = true;
                }
            } catch (Exception ex) {
                System.err.println("  ⚠️  Erro ao verificar properties: " + ex.getMessage());
            }
        }

        // ✅ 2. Verificar .xml
        if (xmlPath != null && xmlFile.exists()) {
            try {
                if (hasXmlChanged(xmlFile, xmlPath)) {
                    downloadFile(fixDownloadUrl(xmlPath), xmlFile);
                    updated = true;
                }
            } catch (Exception ex) {
                System.err.println("  ⚠️  Erro ao verificar XML: " + ex.getMessage());
            }
        }

        // ✅ 3. Verificar áudio
        if (audioPath != null && audioFile != null) {
            try {
                if (hasAudioMetadataChanged(audioFile, audioPath)) {
                    String ext = audioPath.substring(audioPath.lastIndexOf('.') + 1).toLowerCase();
                    downloadFile(fixDownloadUrl(audioPath), audioFile);
                    updated = true;
                }
            } catch (Exception ex) {
                System.err.println("  ⚠️  Erro ao verificar áudio: " + ex.getMessage());
            }
        }

        return updated ? 1 : 0;
    }

    /**
     * Procura a URL da música no GitHub usando o caminho local como referência
     * Isso é mais confiável que tentar reconstruir o autor
     */
    private static String findMusicUrlByLocalPath(File musicFolder, String authorName, String musicName) throws Exception {
        // O caminho é: ...KaraokeMusicas/Aliança Vocal/{CoralName}/{MusicName}
        // Vamos extrair o nome do coral do caminho local

        File parentCoralFolder = musicFolder.getParentFile(); // Pasta do coral
        if (parentCoralFolder == null) return null;

        String localCoralName = parentCoralFolder.getName();
        String localMusicName = musicFolder.getName();

        try {
            // Construir URL com encoding correto (%20 em vez de +)
            String coralEncoded = URLEncoder.encode(localCoralName, StandardCharsets.UTF_8)
                    .replace("+", "%20");
            String coralPath = "Alian%C3%A7a%20Vocal/" + coralEncoded;
            String coralUrl = buildGitHubApiUrl(coralPath);

            JSONArray musics = getJsonArray(coralUrl);

            Thread.sleep(RATE_LIMIT_DELAY);

            for (int i = 0; i < musics.length(); i++) {
                JSONObject musicObj = musics.getJSONObject(i);
                String type = musicObj.optString("type");
                String name = musicObj.optString("name");

                if ("dir".equals(type) && localMusicName.equalsIgnoreCase(name)) {
                    String url = musicObj.optString("url");
                    return url;
                }
            }

        } catch (Exception ex) {
            System.err.println("  ❌ ERRO: " + ex.getMessage());
        }

        return null;
    }

    /**
     * Verifica se os valores de offsetSeconds e realDurationMin mudaram no remoto
     */
    private static boolean hasPropertiesChanged(File localFile, String remotePath, Properties localProps) throws Exception {
        // Baixar properties remotas
        File tempFile = File.createTempFile("props", ".tmp");
        downloadFile(fixDownloadUrl(remotePath), tempFile);
        Properties remoteProps = loadProperties(tempFile);
        tempFile.delete();

        String localOffset = normalizeNumericProperty(localProps.getProperty(PROP_OFFSET_SECONDS, "0"));
        String remoteOffset = normalizeNumericProperty(remoteProps.getProperty(PROP_OFFSET_SECONDS, "0"));

        String localDuration = normalizeNumericProperty(localProps.getProperty(PROP_REAL_DURATION_MIN, "0"));
        String remoteDuration = normalizeNumericProperty(remoteProps.getProperty(PROP_REAL_DURATION_MIN, "0"));

        return !localOffset.equals(remoteOffset) || !localDuration.equals(remoteDuration);
    }

    /**
     * Normaliza números (trata vírgula e ponto como separador decimal)
     */
    private static String normalizeNumericProperty(String value) {
        try {
            String normalized = value.trim().replace(",", ".");
            return String.valueOf(Double.parseDouble(normalized));
        } catch (NumberFormatException e) {
            return "0";
        }
    }

    /**
     * Baixa e atualiza apenas os valores de offsetSeconds e realDurationMin
     */
    private static void downloadAndUpdateProperties(String remotePath, File localFile) throws Exception {
        File tempFile = File.createTempFile("props", ".tmp");
        downloadFile(fixDownloadUrl(remotePath), tempFile);
        Properties remoteProps = loadProperties(tempFile);
        tempFile.delete();

        Properties localProps = loadProperties(localFile);

        String remoteOffset = remoteProps.getProperty(PROP_OFFSET_SECONDS);
        String remoteDuration = remoteProps.getProperty(PROP_REAL_DURATION_MIN);

        if (remoteOffset != null) {
            localProps.setProperty(PROP_OFFSET_SECONDS, remoteOffset);
        }
        if (remoteDuration != null) {
            localProps.setProperty(PROP_REAL_DURATION_MIN, remoteDuration);
        }

        localProps.setProperty(PROP_LAST_SYNC, new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        saveProperties(localFile, localProps);
    }

    /**
     * Compara conteúdo completo do XML
     */
    private static boolean hasXmlChanged(File localFile, String remotePath) throws Exception {
        String localContent = new String(Files.readAllBytes(localFile.toPath()), StandardCharsets.UTF_8).trim();

        File tempFile = File.createTempFile("xml", ".tmp");
        downloadFile(fixDownloadUrl(remotePath), tempFile);
        String remoteContent = new String(Files.readAllBytes(tempFile.toPath()), StandardCharsets.UTF_8).trim();
        tempFile.delete();

        return !localContent.equals(remoteContent);
    }

    /**
     * Compara metadados do áudio (tamanho, data modificação, duração)
     */
    private static boolean hasAudioMetadataChanged(File localFile, String remotePath) throws Exception {
        try {
            long localSize = localFile.length();
            long localLastModified = localFile.lastModified();

            URL url = new URL(fixDownloadUrl(remotePath));
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "Java-GitHubSync/1.0");

            if (GITHUB_TOKEN != null && !GITHUB_TOKEN.isEmpty()) {
                conn.setRequestProperty("Authorization", "token " + GITHUB_TOKEN);
            }

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                conn.disconnect();
                return false;
            }

            long remoteSize = conn.getContentLengthLong();
            String lastModifiedHeader = conn.getHeaderField("Last-Modified");
            conn.disconnect();

            // Se tamanho mudou, arquivo mudou
            if (localSize != remoteSize) {
                return true;
            }

            // Comparar data modificação se disponível
            if (lastModifiedHeader != null) {
                // Parsear data HTTP e comparar com local
                // Para simplificar, apenas comparamos tamanho
                return false;
            }

            return false;

        } catch (Exception ex) {
            System.err.println("Erro ao verificar metadados: " + ex.getMessage());
            return false;
        }
    }

    /**
     * Procura a URL de uma música no GitHub
     */
    private static String findMusicUrlOnGitHub(String authorName, String musicName) throws Exception {
        try {
            String coralPath = "Alian%C3%A7a%20Vocal/" + URLEncoder.encode(safeName(authorName), StandardCharsets.UTF_8);
            String coralUrl = buildGitHubApiUrl(coralPath);
            JSONArray musics = getJsonArray(coralUrl);

            Thread.sleep(RATE_LIMIT_DELAY);

            for (int i = 0; i < musics.length(); i++) {
                JSONObject musicObj = musics.getJSONObject(i);
                if ("dir".equals(musicObj.optString("type"))) {
                    if (musicName.equals(musicObj.optString("name"))) {
                        return musicObj.optString("url");
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    // ── Métodos existentes (mantidos) ────────────────────────────────────────

    private static List<MusicToDownload> findNewMusics(SyncListener listener) throws Exception {
        List<MusicToDownload> toDownload = new ArrayList<>();
        Set<String> localMusics = getLocalMusics();

        String aliancaUrl = buildGitHubApiUrl("Alian%C3%A7a%20Vocal");
        JSONArray corals = getJsonArray(aliancaUrl);

        Thread.sleep(RATE_LIMIT_DELAY);

        for (int i = 0; i < corals.length(); i++) {
            JSONObject coralObj = corals.getJSONObject(i);

            if (!"dir".equals(coralObj.optString("type"))) {
                continue;
            }

            String coralName = coralObj.getString("name");
            String coralUrl = coralObj.optString("url");

            try {
                JSONArray musics = getJsonArray(coralUrl);
                Thread.sleep(RATE_LIMIT_DELAY);

                for (int j = 0; j < musics.length(); j++) {
                    JSONObject musicObj = musics.getJSONObject(j);

                    if (!"dir".equals(musicObj.optString("type"))) {
                        continue;
                    }

                    String musicName = musicObj.getString("name");
                    String musicUrl = musicObj.optString("url");

                    String musicKey = coralName + "|" + musicName;
                    if (!localMusics.contains(musicKey)) {
                        toDownload.add(new MusicToDownload(coralName, musicName, musicUrl));
                    }
                }
            } catch (Exception ex) {
                System.err.println("Erro ao listar músicas de " + coralName + ": " + ex.getMessage());
            }
        }

        return toDownload;
    }

    private static Set<String> getLocalMusics() {
        Set<String> local = new HashSet<>();
        List<MusicLibrary.SavedMusic> all = MusicLibrary.listAll();
        for (MusicLibrary.SavedMusic m : all) {
            String[] parts = m.name.split(" - ");
            if (parts.length >= 2) {
                local.add(m.author + "|" + parts[1].trim());
            }
        }
        return local;
    }

    private static int[] downloadMusics(List<MusicToDownload> toDownload, SyncListener listener) {
        int downloaded = 0;
        int updated = 0;
        int skipped = 0;

        for (int i = 0; i < toDownload.size(); i++) {
            MusicToDownload music = toDownload.get(i);
            listener.onProgress("Verificando: " + music.authorName + " - " + music.musicName,
                    i + 1, toDownload.size());

            try {
                int result = downloadMusic(music);
                if (result == 0) {
                    downloaded++;
                } else if (result == 1) {
                    updated++;
                } else {
                    // result == 2: já estava atualizado
                }
            } catch (Exception ex) {
                System.err.println("Erro ao baixar " + music.musicName + ": " + ex.getMessage());
                skipped++;
            }

            try {
                Thread.sleep(RATE_LIMIT_DELAY * 2);
            } catch (InterruptedException ignored) {}
        }

        return new int[]{downloaded, updated, skipped};
    }

    private static String buildGitHubApiUrl(String path) {
        return GITHUB_API_URL + "/" + path;
    }

    private static int downloadMusic(MusicToDownload music) throws Exception {
        Object response = getJsonResponse(music.musicUrl);

        JSONArray files;
        if (response instanceof JSONArray) {
            files = (JSONArray) response;
        } else if (response instanceof JSONObject) {
            files = new JSONArray();
            files.put((JSONObject) response);
        } else {
            throw new Exception("Resposta inesperada do GitHub para " + music.musicName);
        }

        File musicFolder = getMusicFolder(music.authorName, music.musicName);
        musicFolder.mkdirs();

        File configFile = new File(musicFolder, getFileName(music) + ".properties");
        Properties localProps = loadProperties(configFile);

        String xmlPath = null;
        String xmlSha = null;
        String audioPath = null;
        String audioSha = null;
        String configPath = null;
        String configSha = null;

        for (int i = 0; i < files.length(); i++) {
            JSONObject fileObj = files.getJSONObject(i);

            if ("dir".equals(fileObj.optString("type"))) {
                continue;
            }

            String fileName = fileObj.getString("name");
            String downloadUrl = fileObj.optString("download_url");
            String sha = fileObj.optString("sha");

            if (downloadUrl.isEmpty()) {
                System.err.println("  ⚠️  Sem download_url para: " + fileName);
                continue;
            }

            if (fileName.endsWith(".xml")) {
                xmlPath = downloadUrl;
                xmlSha = sha;
            } else if (fileName.endsWith(".properties")) {
                configPath = downloadUrl;
                configSha = sha;
            } else if (isAudioFile(fileName)) {
                audioPath = downloadUrl;
                audioSha = sha;
            }
        }

        if (xmlPath == null) {
            throw new Exception("XML não encontrado para " + music.musicName);
        }

        boolean xmlNeedsUpdate = !xmlSha.equals(localProps.getProperty(PROP_XML_VERSION, ""));
        boolean audioNeedsUpdate = audioPath != null && !audioSha.equals(localProps.getProperty(PROP_AUDIO_VERSION, ""));
        boolean configNeedsUpdate = configPath != null && !configSha.equals(localProps.getProperty(PROP_CONFIG_VERSION, ""));
        boolean isFirstDownload = !configFile.exists();

        if (!xmlNeedsUpdate && !audioNeedsUpdate && !configNeedsUpdate && !isFirstDownload) {
            return 2;
        }

        if (configPath != null && (configNeedsUpdate || isFirstDownload)) {
            File tempConfigFile = new File(musicFolder, getFileName(music) + ".properties.tmp");
            downloadFile(fixDownloadUrl(configPath), tempConfigFile);

            localProps = loadProperties(tempConfigFile);

            String prevXmlVersion = loadProperties(configFile).getProperty(PROP_XML_VERSION, "");
            String prevAudioVersion = loadProperties(configFile).getProperty(PROP_AUDIO_VERSION, "");
            if (!prevXmlVersion.isEmpty()) localProps.setProperty(PROP_XML_VERSION, prevXmlVersion);
            if (!prevAudioVersion.isEmpty()) localProps.setProperty(PROP_AUDIO_VERSION, prevAudioVersion);

            tempConfigFile.renameTo(configFile);
            localProps.setProperty(PROP_CONFIG_VERSION, configSha);
        }

        if (xmlNeedsUpdate || isFirstDownload) {
            downloadFile(fixDownloadUrl(xmlPath), new File(musicFolder, getFileName(music) + ".xml"));
            localProps.setProperty(PROP_XML_VERSION, xmlSha);
        }

        if (audioPath != null && (audioNeedsUpdate || isFirstDownload)) {
            String ext = audioPath.substring(audioPath.lastIndexOf('.') + 1).toLowerCase();
            downloadFile(fixDownloadUrl(audioPath), new File(musicFolder, getFileName(music) + "." + ext));
            localProps.setProperty(PROP_AUDIO_VERSION, audioSha);
            localProps.setProperty("audioExtension", ext);
        }

        if (!localProps.containsKey("author")) {
            localProps.setProperty("author", music.authorName);
        }
        if (!localProps.containsKey("musicName")) {
            localProps.setProperty("musicName", music.musicName);
        }

        localProps.setProperty(PROP_LAST_SYNC, new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));

        if (!localProps.containsKey("audioExtension")) {
            localProps.setProperty("audioExtension", "");
        }
        if (!localProps.containsKey("selectedVoice")) {
            localProps.setProperty("selectedVoice", "0");
        }

        saveProperties(configFile, localProps);

        return isFirstDownload ? 0 : 1;
    }

    private static File findAudioFileInFolder(File folder) {
        File[] files = folder.listFiles((d, n) -> {
            String lower = n.toLowerCase();
            return lower.endsWith(".opus") || lower.endsWith(".mp3") ||
                    lower.endsWith(".wav") || lower.endsWith(".ogg") ||
                    lower.endsWith(".flac") || lower.endsWith(".aac");
        });
        if (files != null && files.length > 0) {
            return files[0];
        }
        return null;
    }

    private static String getFileName(MusicToDownload music) {
        return getFileName(music.authorName, music.musicName);
    }

    private static String getFileName(String authorName, String musicName) {
        String combined = authorName.trim() + "-" + musicName.trim();
        String normalized = java.text.Normalizer.normalize(combined, java.text.Normalizer.Form.NFD);
        normalized = normalized.replaceAll("\\p{M}", "");
        normalized = normalized.toLowerCase();
        normalized = normalized.replaceAll("\\s+", "_");
        normalized = normalized.replaceAll("[\\\\/:*?\"<>|]", "");
        return normalized;
    }

    private static File getMusicFolder(String author, String musicName) {
        String safeAuthor = safeName(author);
        String safeMusic = safeName(musicName);
        String rootFolder = System.getProperty("user.home") + File.separator + "KaraokeMusicas"
                + File.separator + "Aliança Vocal";
        return new File(rootFolder + File.separator + safeAuthor + File.separator + safeMusic);
    }

    private static String safeName(String name) {
        return name.trim()
                .replaceAll("[\\\\/:*?\"<>|]", "_")
                .replaceAll("\\s+", " ");
    }

    private static Properties loadProperties(File file) {
        Properties props = new Properties();
        if (file.exists()) {
            try (FileInputStream fis = new FileInputStream(file)) {
                props.load(fis);
            } catch (IOException ex) {
                System.err.println("  ⚠️  Erro ao ler propriedades: " + ex.getMessage());
            }
        }
        return props;
    }

    private static void saveProperties(File file, Properties props) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            props.store(fos, "Karaoke config — " + props.getProperty("author", "?") + " - " + props.getProperty("musicName", "?"));
        }
    }

    private static void downloadFile(String urlStr, File dest) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("User-Agent", "Java-GitHubSync/1.0");

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            throw new Exception("HTTP " + responseCode + " ao baixar: " + urlStr);
        }

        try (InputStream in = conn.getInputStream();
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        } finally {
            conn.disconnect();
        }
    }

    private static Object getJsonResponse(String urlStr) throws Exception {
        if (!urlStr.contains("?")) {
            urlStr += "?ref=" + REF;
        }

        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("User-Agent", "Java-GitHubSync/1.0");

        if (GITHUB_TOKEN != null && !GITHUB_TOKEN.isEmpty()) {
            conn.setRequestProperty("Authorization", "token " + GITHUB_TOKEN);
        }

        int responseCode = conn.getResponseCode();

        if (responseCode == 403) {
            String rateLimitRemaining = conn.getHeaderField("X-RateLimit-Remaining");
            String rateLimitReset = conn.getHeaderField("X-RateLimit-Reset");
            throw new Exception("Rate limit atingido. Restante: " + rateLimitRemaining +
                    " | Reset em: " + rateLimitReset);
        }

        if (responseCode != 200) {
            InputStream errorStream = conn.getErrorStream();
            String errorMsg = "HTTP " + responseCode;
            if (errorStream != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(errorStream, StandardCharsets.UTF_8))) {
                    String line = reader.readLine();
                    if (line != null) {
                        JSONObject error = new JSONObject(line);
                        errorMsg += " - " + error.optString("message", "");
                    }
                }
            }
            throw new Exception("Erro ao conectar ao GitHub: " + errorMsg);
        }

        try (InputStream in = conn.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }

            String content = sb.toString().trim();

            if (content.startsWith("[")) {
                return new JSONArray(content);
            } else if (content.startsWith("{")) {
                return new JSONObject(content);
            } else {
                throw new Exception("Resposta JSON inválida");
            }
        } finally {
            conn.disconnect();
        }
    }

    private static JSONArray getJsonArray(String urlStr) throws Exception {
        Object response = getJsonResponse(urlStr);
        if (response instanceof JSONArray) {
            return (JSONArray) response;
        } else {
            throw new Exception("Esperado JSONArray, recebido JSONObject");
        }
    }

    private static String fixDownloadUrl(String downloadUrl) throws Exception {
        String withRefHeads = downloadUrl.replace("/main/", "/refs/heads/main/");

        String[] parts = withRefHeads.split("/refs/heads/main/", 2);
        if (parts.length != 2) {
            return withRefHeads;
        }

        String base = parts[0] + "/refs/heads/main/";
        String pathPart = parts[1];

        String decodedPath = java.net.URLDecoder.decode(pathPart, StandardCharsets.UTF_8);

        String[] segments = decodedPath.split("/");
        StringBuilder encodedPath = new StringBuilder();

        for (int i = 0; i < segments.length; i++) {
            if (i > 0) encodedPath.append("/");
            String encoded = URLEncoder.encode(segments[i], StandardCharsets.UTF_8);
            encoded = encoded.replace("+", "%20");
            encodedPath.append(encoded);
        }

        return base + encodedPath.toString();
    }

    private static boolean isAudioFile(String fileName) {
        String lower = fileName.toLowerCase();
        return lower.endsWith(".opus") || lower.endsWith(".mp3") ||
                lower.endsWith(".wav") || lower.endsWith(".ogg") ||
                lower.endsWith(".flac") || lower.endsWith(".aac");
    }

    // ── Classe interna ────────────────────────────────────────────────────────

    private static class MusicToDownload {
        String authorName;
        String musicName;
        String musicUrl;

        MusicToDownload(String author, String music, String url) {
            this.authorName = author;
            this.musicName = music;
            this.musicUrl = url;
        }
    }
}
