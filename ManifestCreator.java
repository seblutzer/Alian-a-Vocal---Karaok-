package com.karaoke;

import org.json.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Responsável por gerar e atualizar manifests de sincronização
 */
public class ManifestCreator {

    private static final String GITHUB_API_URL = GitHubConfigManager.getApiUrl();
    private static final String GITHUB_REPO = GitHubConfigManager.getRepo();
    private static final String GITHUB_TOKEN = GitHubConfigManager.getToken();
    private static final String REF = GitHubConfigManager.getRef();
    private static final long RATE_LIMIT_DELAY = GitHubConfigManager.getUploadRateLimit();

    public interface ManifestListener {
        void onProgress(String message, int current, int total);
        void onError(String error);
        void onComplete(boolean success, int totalMusics);
    }

    public static void generateCompleteManifestFromGitHub(ManifestListener listener) {
        new Thread(() -> {
            try {
                listener.onProgress("📡 Escaneando todas as músicas...", 0, 0);
                JSONObject manifest = buildCompleteManifestFromGitHub(listener);

                listener.onProgress("📝 Fazendo upload do manifest...", 0, 0);
                uploadManifest(manifest);

                int totalMusics = manifest.optJSONObject("musics").length();
                listener.onComplete(true, totalMusics);
            } catch (Exception ex) {
                listener.onError("Erro ao gerar manifest: " + ex.getMessage());
                ex.printStackTrace();
            }
        }).start();
    }

    private static JSONObject buildCompleteManifestFromGitHub(ManifestListener listener) throws Exception {
        JSONObject manifest = new JSONObject();
        manifest.put("version", "1.0");
        manifest.put("lastUpdated", getCurrentTimestamp());
        JSONObject musics = new JSONObject();

        String aliancaUrl = GITHUB_API_URL + "/Alian%C3%A7a%20Vocal?ref=" + REF;
        JSONArray corals = getDirectoriesOnly(aliancaUrl);

        Thread.sleep(RATE_LIMIT_DELAY);
        Set<String> processedCorals = new HashSet<>();

        for (int i = 0; i < corals.length(); i++) {
            JSONObject coralObj = corals.getJSONObject(i);
            String coralName = coralObj.getString("name");

            if (processedCorals.contains(coralName)) {
                continue;
            }
            processedCorals.add(coralName);

            listener.onProgress("🎤 Processando coral: " + coralName, i + 1, corals.length());

            try {
                String coralUrl = coralObj.optString("url");
                JSONArray musicsInCoral = getDirectoriesOnly(coralUrl);
                Thread.sleep(RATE_LIMIT_DELAY);

                for (int j = 0; j < musicsInCoral.length(); j++) {
                    JSONObject musicObj = musicsInCoral.getJSONObject(j);
                    String musicName = musicObj.getString("name");
                    String musicUrl = musicObj.optString("url");

                    JSONObject musicEntry = buildMusicEntryFromGitHub(coralName, musicName, musicUrl);

                    if (musicEntry != null) {
                        String musicKey = generateMusicKey(coralName, coralName + " - " + musicName);
                        musics.put(musicKey, musicEntry);
                    }

                    Thread.sleep(RATE_LIMIT_DELAY);
                }
            } catch (Exception ex) {
                System.err.println("⚠️ Erro ao processar coral " + coralName + ": " + ex.getMessage());
            }
        }

        manifest.put("musics", musics);
        return manifest;
    }

    private static JSONArray getDirectoriesOnly(String urlStr) throws Exception {
        JSONArray allItems = getJsonArray(urlStr);
        JSONArray directoriesOnly = new JSONArray();

        for (int i = 0; i < allItems.length(); i++) {
            JSONObject item = allItems.getJSONObject(i);
            if ("dir".equals(item.optString("type"))) {
                directoriesOnly.put(item);
            }
        }

        return directoriesOnly;
    }

    private static JSONObject buildMusicEntryFromGitHub(String coralName, String musicName, String musicUrl) throws Exception {
        JSONArray files = getJsonArray(musicUrl);
        Thread.sleep(RATE_LIMIT_DELAY);

        String propsDownloadUrl = null;
        String xmlDownloadUrl = null;
        String audioDownloadUrl = null;
        String audioExt = "opus";

        for (int i = 0; i < files.length(); i++) {
            JSONObject fileObj = files.getJSONObject(i);

            if ("dir".equals(fileObj.optString("type"))) {
                continue;
            }

            String fileName = fileObj.getString("name");
            String downloadUrl = fileObj.optString("download_url");

            if (downloadUrl == null || downloadUrl.isEmpty()) {
                continue;
            }

            if (fileName.endsWith(".properties")) {
                propsDownloadUrl = downloadUrl;
            } else if (fileName.endsWith(".xml")) {
                xmlDownloadUrl = downloadUrl;
            } else if (isAudioFile(fileName)) {
                audioDownloadUrl = downloadUrl;
                audioExt = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
            }
        }

        if (propsDownloadUrl == null || xmlDownloadUrl == null) {
            return null;
        }

        JSONObject musicEntry = new JSONObject();
        musicEntry.put("author", coralName);
        musicEntry.put("musicName", musicName);

        try {
            Map<String, String> propsData = downloadAndParseProperties(propsDownloadUrl);

            JSONObject propertiesObj = new JSONObject();
            propertiesObj.put("offsetSeconds", propsData.getOrDefault("offsetSeconds", "0"));
            propertiesObj.put("realDurationMin", propsData.getOrDefault("realDurationMin", "0"));
            musicEntry.put("properties", propertiesObj);
        } catch (Exception ex) {
            return null;
        }

        try {
            String xmlHash = calculateRemoteFileHash(xmlDownloadUrl);
            long xmlSize = getRemoteFileSize(xmlDownloadUrl);
            JSONObject xmlObj = new JSONObject();
            xmlObj.put("hash", xmlHash);
            xmlObj.put("size", xmlSize);
            musicEntry.put("xml", xmlObj);
        } catch (Exception ex) {
            return null;
        }

        if (audioDownloadUrl != null) {
            try {
                String audioHash = calculateRemoteFileHash(audioDownloadUrl);
                long audioSize = getRemoteFileSize(audioDownloadUrl);
                JSONObject audioObj = new JSONObject();
                audioObj.put("hash", audioHash);
                audioObj.put("size", audioSize);
                audioObj.put("format", audioExt);
                musicEntry.put("audio", audioObj);
            } catch (Exception ex) {
            }
        }

        return musicEntry;
    }

    private static Map<String, String> downloadAndParseProperties(String downloadUrl) throws Exception {
        Map<String, String> props = new HashMap<>();
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

        try (InputStream in = conn.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#") || !line.contains("=")) {
                    continue;
                }
                String[] parts = line.split("=", 2);
                if (parts.length == 2) {
                    props.put(parts[0].trim(), parts[1].trim());
                }
            }
        } finally {
            conn.disconnect();
        }

        return props;
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

    public static void updateManifestWithSingleMusic(MusicLibrary.SavedMusic music, String remoteMusicPath) throws Exception {
        JSONObject manifest = downloadManifest();
        JSONObject musics = manifest.optJSONObject("musics");
        if (musics == null) {
            musics = new JSONObject();
            manifest.put("musics", musics);
        }

        String musicKey = generateMusicKey(music.author, music.name);

        File propsFile = findPropertiesFile(music.folder);
        Map<String, String> propsData = new HashMap<>();
        if (propsFile != null && propsFile.exists()) {
            Properties props = new Properties();
            try (FileInputStream fis = new FileInputStream(propsFile)) {
                props.load(fis);
                propsData.put("offsetSeconds", props.getProperty("offsetSeconds", "0"));
                propsData.put("realDurationMin", props.getProperty("realDurationMin", "0"));
            }
        }

        String xmlHash = calculateFileHash(music.xmlFile);
        String audioHash = music.audioFile != null ? calculateFileHash(music.audioFile) : "";

        JSONObject musicEntry = new JSONObject();
        musicEntry.put("author", music.author);
        musicEntry.put("musicName", music.name.split(" - ")[1].trim());

        JSONObject propertiesObj = new JSONObject();
        propertiesObj.put("offsetSeconds", propsData.getOrDefault("offsetSeconds", "0"));
        propertiesObj.put("realDurationMin", propsData.getOrDefault("realDurationMin", "0"));
        musicEntry.put("properties", propertiesObj);

        JSONObject xmlObj = new JSONObject();
        xmlObj.put("hash", xmlHash);
        xmlObj.put("size", music.xmlFile.length());
        musicEntry.put("xml", xmlObj);

        if (music.audioFile != null && music.audioFile.exists()) {
            JSONObject audioObj = new JSONObject();
            audioObj.put("hash", audioHash);
            audioObj.put("size", music.audioFile.length());
            String audioExt = music.audioFile.getName().substring(music.audioFile.getName().lastIndexOf('.') + 1);
            audioObj.put("format", audioExt.toLowerCase());
            musicEntry.put("audio", audioObj);
        }

        musics.put(musicKey, musicEntry);
        manifest.put("lastUpdated", getCurrentTimestamp());

        uploadManifest(manifest);
    }

    public static JSONObject downloadManifest() throws Exception {
        String url = String.format("https://raw.githubusercontent.com/%s/%s/main/sync-manifest.json",
                GITHUB_REPO.split("/")[0],
                GITHUB_REPO.split("/")[1]);

        return new JSONObject(downloadTextFile(url));
    }

    private static void uploadManifest(JSONObject manifest) throws Exception {
        String base64Content = Base64.getEncoder().encodeToString(
                manifest.toString(2).getBytes(StandardCharsets.UTF_8));

        String message = "🔄 Atualizar sync-manifest.json - " +
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());

        String manifestSha = getRemoteFileSha("sync-manifest.json");
        uploadToGitHub("sync-manifest.json", base64Content, manifestSha, message);
    }

    private static String calculateRemoteFileHash(String downloadUrl) throws Exception {
        downloadUrl = fixGitHubRawUrl(downloadUrl);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");

        URL url = new URL(downloadUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("User-Agent", "Java-GitHubSync/1.0");

        try (InputStream in = conn.getInputStream()) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        } finally {
            conn.disconnect();
        }

        return formatHash(digest.digest());
    }

    private static String calculateFileHash(File file) throws Exception {
        if (file == null || !file.exists()) {
            return "";
        }

        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        }

        return formatHash(digest.digest());
    }

    private static String formatHash(byte[] hashBytes) {
        StringBuilder hashString = new StringBuilder();
        for (byte b : hashBytes) {
            hashString.append(String.format("%02x", b));
        }
        return hashString.toString();
    }

    private static long getRemoteFileSize(String downloadUrl) throws Exception {
        downloadUrl = fixGitHubRawUrl(downloadUrl);
        URL url = new URL(downloadUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("HEAD");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("User-Agent", "Java-GitHubSync/1.0");

        try {
            conn.connect();
            return conn.getContentLengthLong();
        } finally {
            conn.disconnect();
        }
    }

    private static String generateMusicKey(String author, String musicName) {
        String combined = author.trim() + "_" + musicName.split(" - ")[1].trim();
        String normalized = java.text.Normalizer.normalize(combined, java.text.Normalizer.Form.NFD);
        normalized = normalized.replaceAll("\\p{M}", "");
        normalized = normalized.toLowerCase();
        normalized = normalized.replaceAll("\\s+", "_");
        normalized = normalized.replaceAll("[\\\\/:*?\"<>|]", "");
        return normalized;
    }

    private static File findPropertiesFile(File folder) {
        File[] files = folder.listFiles((d, n) -> n.endsWith(".properties"));
        return (files != null && files.length > 0) ? files[0] : null;
    }

    private static String getRemoteFileSha(String remotePath) throws Exception {
        String url = GITHUB_API_URL + "/" + remotePath + "?ref=" + REF;

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("User-Agent", "Java-GitHubSync/1.0");
        conn.setRequestProperty("Authorization", "token " + GITHUB_TOKEN);

        int responseCode = conn.getResponseCode();
        if (responseCode == 404 || responseCode != 200) {
            return null;
        }

        try (InputStream in = conn.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }

            JSONObject obj = new JSONObject(sb.toString());
            return obj.optString("sha", null);
        } finally {
            conn.disconnect();
        }
    }

    private static void uploadToGitHub(String remotePath, String base64Content,
                                       String existingSha, String message) throws Exception {
        String url = GITHUB_API_URL + "/" + remotePath;

        JSONObject payload = new JSONObject();
        payload.put("message", message);
        payload.put("content", base64Content);
        payload.put("branch", REF);

        if (existingSha != null && !existingSha.trim().isEmpty()) {
            payload.put("sha", existingSha);
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("PUT");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("User-Agent", "Java-GitHubSync/1.0");
        conn.setRequestProperty("Authorization", "token " + GITHUB_TOKEN);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = payload.toString().getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        int responseCode = conn.getResponseCode();
        if (responseCode != 201 && responseCode != 200) {
            throw new Exception("HTTP " + responseCode);
        }

        conn.disconnect();
    }

    private static String downloadTextFile(String urlStr) throws Exception {
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
        if (responseCode != 200) {
            throw new Exception("HTTP " + responseCode);
        }

        try (InputStream in = conn.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    private static JSONArray getJsonArray(String urlStr) throws Exception {
        if (!urlStr.contains("?")) {
            urlStr += "?ref=" + REF;
        }

        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("User-Agent", "Java-GitHubSync/1.0");
        conn.setRequestProperty("Authorization", "token " + GITHUB_TOKEN);

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            throw new Exception("HTTP " + responseCode);
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
            } else {
                throw new Exception("Esperado JSONArray");
            }
        } finally {
            conn.disconnect();
        }
    }

    private static boolean isAudioFile(String fileName) {
        String lower = fileName.toLowerCase();
        return lower.endsWith(".opus") || lower.endsWith(".mp3") ||
                lower.endsWith(".wav") || lower.endsWith(".ogg") ||
                lower.endsWith(".flac") || lower.endsWith(".aac");
    }

    private static String getCurrentTimestamp() {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").format(new Date());
    }
}