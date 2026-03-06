package com.karaoke;

import org.json.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;

/**
 * Sistema de sincronização otimizado com GitHub usando manifest.json
 */
public class GitHubSyncManager {

    private static final String MANIFEST_URL = "https://raw.githubusercontent.com/seblutzer/alianca_vocal_musics_files/refs/heads/main/sync-manifest.json";
    private static final long RATE_LIMIT_DELAY = GitHubConfigManager.getSyncRateLimit();

    private static final Set<String> SYNC_KEYS = new HashSet<>(Arrays.asList(
            "offsetSeconds",
            "realDurationMin"
    ));

    private static final String PROP_LAST_SYNC = "last_sync_date";

    public interface SyncListener {
        void onProgress(String message, int current, int total);
        void onError(String error);
        void onComplete(int downloaded, int updated, int skipped);
    }

    public static void syncWithGitHub(SyncListener listener) {
        new Thread(() -> {
            try {
                listener.onProgress("📡 Baixando manifest...", 0, 0);
                JSONObject manifest = downloadManifest();

                if (manifest == null) {
                    throw new Exception("Falha ao baixar manifest.json");
                }

                int[] results = new int[]{0, 0, 0};

                listener.onProgress("🔍 Verificando músicas locais...", 0, 0);
                int[] verifyResults = verifyLocalMusicsAgainstManifest(manifest, listener);
                results[0] += verifyResults[0];
                results[1] += verifyResults[1];
                results[2] += verifyResults[2];

                List<MusicToDownload> toDownload = findNewMusics(manifest);
                if (!toDownload.isEmpty()) {
                    listener.onProgress("⬇️  Baixando " + toDownload.size() + " música(s)...", 0, toDownload.size());
                    int[] downloadResults = downloadMusics(toDownload, manifest, listener);
                    results[0] += downloadResults[0];
                    results[1] += downloadResults[1];
                    results[2] += downloadResults[2];
                }

                listener.onComplete(results[0], results[1], results[2]);

            } catch (Exception ex) {
                listener.onError("❌ Erro: " + ex.getMessage());
                ex.printStackTrace();
            }
        }).start();
    }

    private static int[] verifyLocalMusicsAgainstManifest(JSONObject manifest, SyncListener listener) throws Exception {
        int updated = 0;
        int skipped = 0;

        List<MusicLibrary.SavedMusic> allLocal = getAllLocalMusics();
        JSONObject musicsInManifest = manifest.optJSONObject("musics");

        if (musicsInManifest == null) {
            return new int[]{0, 0, allLocal.size()};
        }

        Map<String, JSONObject> manifestMap = new HashMap<>();
        Iterator<String> keys = musicsInManifest.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            manifestMap.put(key, musicsInManifest.getJSONObject(key));
        }

        for (int i = 0; i < allLocal.size(); i++) {
            MusicLibrary.SavedMusic music = allLocal.get(i);
            listener.onProgress("🔍 Verificando: " + music.author + " - " + music.name, i + 1, allLocal.size());

            try {
                String localKey = generateMusicKey(music.author, music.name);

                if (!manifestMap.containsKey(localKey)) {
                    skipped++;
                    continue;
                }

                JSONObject remoteData = manifestMap.get(localKey);
                int result = verifyAndUpdateMusic(music, remoteData);

                if (result == 1) {
                    updated++;
                }

            } catch (Exception ex) {
                skipped++;
            }
        }

        return new int[]{0, updated, skipped};
    }

    private static int verifyAndUpdateMusic(MusicLibrary.SavedMusic localMusic, JSONObject remoteData) throws Exception {
        File musicFolder = getMusicFolder(localMusic.author, localMusic.name);
        if (!musicFolder.exists()) {
            return 0;
        }

        String fileName = getFileName(localMusic.author, localMusic.name);
        File configFile = new File(musicFolder, fileName + ".properties");
        File xmlFile = new File(musicFolder, fileName + ".xml");
        File audioFile = findAudioFileInFolder(musicFolder);

        if (!configFile.exists()) {
            return 0;
        }

        Properties localProps = loadProperties(configFile);
        String selectedVoice = localProps.getProperty("selectedVoice", "0");
        boolean needsUpdate = false;

        // Verificar properties
        if (remoteData.has("properties")) {
            JSONObject remoteProps = remoteData.getJSONObject("properties");

            if (updatePropertyIfChanged(localProps, remoteProps, "offsetSeconds")) {
                needsUpdate = true;
            }

            if (updatePropertyIfChanged(localProps, remoteProps, "realDurationMin")) {
                needsUpdate = true;
            }
        }

        // Verificar XML
        boolean needDownloadXml = false;
        if (remoteData.has("xml") && xmlFile.exists()) {
            JSONObject remoteXml = remoteData.getJSONObject("xml");
            String remoteHash = remoteXml.optString("hash", "");
            String localHash = calculateFileHash(xmlFile);

            if (!remoteHash.isEmpty() && !remoteHash.equals(localHash)) {
                needDownloadXml = true;
                needsUpdate = true;
            }
        }

        // Verificar áudio
        boolean needDownloadAudio = false;
        if (remoteData.has("audio") && audioFile != null) {
            JSONObject remoteAudio = remoteData.getJSONObject("audio");
            String remoteHash = remoteAudio.optString("hash", "");
            String localHash = calculateFileHash(audioFile);

            if (!remoteHash.isEmpty() && !remoteHash.equals(localHash)) {
                needDownloadAudio = true;
                needsUpdate = true;
            }
        }

        if (needsUpdate) {
            try {
                if (needDownloadXml) {
                    String xmlUrl = buildMusicFileUrl(localMusic.author, localMusic.name, fileName + ".xml");
                    downloadFile(xmlUrl, xmlFile);
                }

                if (needDownloadAudio) {
                    String audioFormat = remoteData.getJSONObject("audio").optString("format", "opus");
                    String audioUrl = buildMusicFileUrl(localMusic.author, localMusic.name, fileName + "." + audioFormat);
                    downloadFile(audioUrl, audioFile);
                }

                localProps.setProperty("selectedVoice", selectedVoice);
                localProps.setProperty(PROP_LAST_SYNC, getCurrentTimestamp());
                saveProperties(configFile, localProps);

                return 1;

            } catch (Exception ex) {
                throw ex;
            }
        }

        return 0;
    }

    private static boolean updatePropertyIfChanged(Properties localProps, JSONObject remoteProps, String key) {
        if (!remoteProps.has(key)) {
            return false;
        }

        String remoteValue = remoteProps.getString(key);
        String localValue = localProps.getProperty(key, "0");

        if (!normalizeNumeric(remoteValue).equals(normalizeNumeric(localValue))) {
            localProps.setProperty(key, remoteValue);
            return true;
        }

        return false;
    }

    private static JSONObject downloadManifest() throws Exception {
        File cacheFile = new File(System.getProperty("user.home"), ".karaoke_manifest_cache.json");

        try {
            String content = downloadTextFile(MANIFEST_URL);
            JSONObject manifest = new JSONObject(content);
            Files.write(cacheFile.toPath(), content.getBytes(StandardCharsets.UTF_8));
            return manifest;
        } catch (Exception ex) {
            if (cacheFile.exists()) {
                String cached = new String(Files.readAllBytes(cacheFile.toPath()), StandardCharsets.UTF_8);
                return new JSONObject(cached);
            }
            throw ex;
        }
    }

    private static List<MusicToDownload> findNewMusics(JSONObject manifest) throws Exception {
        List<MusicToDownload> toDownload = new ArrayList<>();
        Set<String> localKeys = new HashSet<>();

        for (MusicLibrary.SavedMusic m : getAllLocalMusics()) {
            localKeys.add(generateMusicKey(m.author, m.name));
        }

        JSONObject musics = manifest.optJSONObject("musics");
        if (musics == null) {
            return toDownload;
        }

        Iterator<String> keys = musics.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!localKeys.contains(key)) {
                JSONObject musicData = musics.getJSONObject(key);
                String author = musicData.getString("author");
                String musicName = musicData.getString("musicName");
                toDownload.add(new MusicToDownload(author, musicName, null));
            }
        }

        return toDownload;
    }

    private static int[] downloadMusics(List<MusicToDownload> toDownload, JSONObject manifest, SyncListener listener) {
        int downloaded = 0;
        int skipped = 0;

        JSONObject musicsObj = manifest.optJSONObject("musics");
        if (musicsObj == null) {
            return new int[]{0, 0, 0};
        }

        for (int i = 0; i < toDownload.size(); i++) {
            MusicToDownload music = toDownload.get(i);
            listener.onProgress("⬇️  Baixando: " + music.authorName + " - " + music.musicName,
                    i + 1, toDownload.size());

            try {
                String key = generateMusicKey(music.authorName, music.musicName);
                JSONObject musicData = musicsObj.optJSONObject(key);

                if (musicData != null) {
                    downloadSingleMusic(music, musicData);
                    downloaded++;
                } else {
                    skipped++;
                }

            } catch (Exception ex) {
                skipped++;
            }

            try {
                Thread.sleep(RATE_LIMIT_DELAY);
            } catch (InterruptedException ignored) {}
        }

        return new int[]{downloaded, 0, skipped};
    }

    private static void downloadSingleMusic(MusicToDownload music, JSONObject manifestData) throws Exception {
        File musicFolder = getMusicFolder(music.authorName, music.musicName);
        musicFolder.mkdirs();

        String fileName = getFileName(music.authorName, music.musicName);
        File configFile = new File(musicFolder, fileName + ".properties");

        downloadFile(buildMusicFileUrl(music.authorName, music.musicName, fileName + ".xml"),
                new File(musicFolder, fileName + ".xml"));

        String audioFormat = manifestData.optJSONObject("audio").optString("format", "opus");
        downloadFile(buildMusicFileUrl(music.authorName, music.musicName, fileName + "." + audioFormat),
                new File(musicFolder, fileName + "." + audioFormat));

        Properties props = new Properties();
        JSONObject remoteProps = manifestData.optJSONObject("properties");
        if (remoteProps != null) {
            props.setProperty("offsetSeconds", remoteProps.optString("offsetSeconds", "0"));
            props.setProperty("realDurationMin", remoteProps.optString("realDurationMin", "0"));
        }

        props.setProperty("selectedVoice", "0");
        props.setProperty("author", music.authorName);
        props.setProperty("musicName", music.musicName);
        props.setProperty("audioExtension", audioFormat);
        props.setProperty(PROP_LAST_SYNC, getCurrentTimestamp());

        saveProperties(configFile, props);
    }

    private static String generateMusicKey(String author, String musicName) {
        String finalName = musicName;

        if (musicName.contains(" - ")) {
            String[] parts = musicName.split(" - ", 2);
            if (parts.length == 2 && parts[0].equalsIgnoreCase(author.trim())) {
                finalName = parts[1];
            }
        }

        String combined = author.trim() + "_" + finalName.trim();
        String normalized = java.text.Normalizer.normalize(combined, java.text.Normalizer.Form.NFD);
        normalized = normalized.replaceAll("\\p{M}", "");
        normalized = normalized.toLowerCase();
        normalized = normalized.replaceAll("\\s+", "_");
        normalized = normalized.replaceAll("[\\\\/:*?\"<>|]", "");
        return normalized;
    }

    private static String calculateFileHash(File file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] fileBytes = Files.readAllBytes(file.toPath());
        byte[] hashBytes = md.digest(fileBytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : hashBytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String normalizeNumeric(String value) {
        try {
            return String.valueOf(Double.parseDouble(value.trim().replace(",", ".")));
        } catch (NumberFormatException e) {
            return "0";
        }
    }

    private static String buildMusicFileUrl(String author, String musicName, String fileName) throws Exception {
        String authorEncoded = URLEncoder.encode(author, StandardCharsets.UTF_8).replace("+", "%20");
        String musicEncoded = URLEncoder.encode(musicName, StandardCharsets.UTF_8).replace("+", "%20");
        String fileEncoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");

        return "https://raw.githubusercontent.com/seblutzer/alianca_vocal_musics_files/refs/heads/main/Alian%C3%A7a%20Vocal/" +
                authorEncoded + "/" + musicEncoded + "/" + fileEncoded;
    }

    private static String downloadTextFile(String downloadUrl) throws Exception {
        downloadUrl = fixGitHubRawUrl(downloadUrl);

        URL url = new URL(downloadUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("User-Agent", "Java-GitHubSync/1.0");

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            throw new Exception("HTTP " + responseCode);
        }

        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append("\n");
            }
        } finally {
            conn.disconnect();
        }

        return content.toString();
    }

    private static String fixGitHubRawUrl(String downloadUrl) throws Exception {
        if (downloadUrl.contains("/refs/heads/")) {
            return downloadUrl;
        }

        String[] urlParts = downloadUrl.split("raw\\.githubusercontent\\.com/", 2);
        if (urlParts.length != 2) {
            return downloadUrl;
        }

        String afterDomain = urlParts[1];
        String[] pathParts = afterDomain.split("/", 4);

        if (pathParts.length < 4) {
            return downloadUrl;
        }

        String user = pathParts[0];
        String repo = pathParts[1];
        String branch = pathParts[2];
        String filePath = pathParts[3];

        String decodedFilePath = java.net.URLDecoder.decode(filePath, StandardCharsets.UTF_8);
        String encodedPath = encodePathCorrectly(decodedFilePath);

        return String.format("https://raw.githubusercontent.com/%s/%s/refs/heads/%s/%s",
                user, repo, branch, encodedPath);
    }

    private static String encodePathCorrectly(String path) throws Exception {
        StringBuilder encoded = new StringBuilder();
        byte[] bytes = path.getBytes(StandardCharsets.UTF_8);

        for (byte b : bytes) {
            char c = (char) (b & 0xFF);

            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ||
                    c == '-' || c == '_' || c == '.' || c == '~' || c == '/' || c == ',') {
                encoded.append(c);
            } else if (c == ' ') {
                encoded.append("%20");
            } else {
                encoded.append(String.format("%%%02X", b & 0xFF));
            }
        }

        return encoded.toString();
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
            throw new Exception("HTTP " + responseCode);
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

    private static Properties loadProperties(File file) {
        Properties props = new Properties();
        if (file.exists()) {
            try (FileInputStream fis = new FileInputStream(file)) {
                props.load(fis);
            } catch (IOException ex) {
                System.err.println("⚠️  Erro ao ler: " + ex.getMessage());
            }
        }
        return props;
    }

    private static void saveProperties(File file, Properties props) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            props.store(fos, "Karaoke Config");
        }
    }

    private static List<MusicLibrary.SavedMusic> getAllLocalMusics() {
        return MusicLibrary.listAll();
    }

    private static File getMusicFolder(String author, String musicName) {
        String cleanMusicName = extractMusicName(author, musicName);
        String safeAuthor = author.trim().replaceAll("[\\\\/:*?\"<>|]", "_");
        String safeMusic = cleanMusicName.trim().replaceAll("[\\\\/:*?\"<>|]", "_");

        return new File(System.getProperty("user.home") + "/KaraokeMusicas/Aliança Vocal/" + safeAuthor + "/" + safeMusic);
    }

    private static String extractMusicName(String author, String fullName) {
        if (fullName.contains(" - ")) {
            String[] parts = fullName.split(" - ", 2);
            if (parts.length == 2 && parts[0].equalsIgnoreCase(author.trim())) {
                return parts[1];
            }
        }
        return fullName;
    }

    private static String getFileName(String author, String musicName) {
        String cleanMusicName = extractMusicName(author, musicName);
        String authorKey = normalizeForFileName(author);
        String musicKey = normalizeForFileName(cleanMusicName);
        return authorKey + "-" + musicKey;
    }

    private static String normalizeForFileName(String text) {
        String normalized = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD);
        normalized = normalized.replaceAll("\\p{M}", "");
        normalized = normalized.toLowerCase();
        normalized = normalized.replaceAll("\\s+", "_");
        normalized = normalized.replaceAll("[\\\\/:*?\"<>|]", "");  // ✅ Vírgula removida da blacklist
        return normalized;
    }

    private static File findAudioFileInFolder(File folder) {
        File[] files = folder.listFiles((d, n) -> {
            String lower = n.toLowerCase();
            return lower.endsWith(".opus") || lower.endsWith(".mp3") ||
                    lower.endsWith(".wav") || lower.endsWith(".ogg");
        });
        return (files != null && files.length > 0) ? files[0] : null;
    }

    private static String getCurrentTimestamp() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    }

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