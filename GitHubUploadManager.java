package com.karaoke;

import org.json.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Gerencia upload inteligente de músicas para o repositório GitHub
 * - Compara hash SHA-256 dos arquivos locais vs remotos
 * - Só faz upload dos arquivos que mudaram
 * - Atualiza sync-manifest.json automaticamente
 */
public class GitHubUploadManager {

    private static final String GITHUB_API_URL = GitHubConfigManager.getApiUrl();
    private static final String GITHUB_REPO = GitHubConfigManager.getRepo();
    private static final String GITHUB_TOKEN = GitHubConfigManager.getToken();
    private static final String REF = GitHubConfigManager.getRef();
    private static final long RATE_LIMIT_DELAY = GitHubConfigManager.getUploadRateLimit();

    private static final String MANIFEST_FILE_NAME = "sync-manifest.json";

    public interface UploadListener {
        void onProgress(String message, int current, int total);
        void onError(String error);
        void onComplete(boolean success, String message);
    }

    public static void uploadMusic(MusicLibrary.SavedMusic music, UploadListener listener) {
        new Thread(() -> {
            try {
                listener.onProgress("Analisando arquivos...", 0, 0);
                uploadMusicInternal(music, listener);
            } catch (Exception ex) {
                listener.onError("Erro no upload: " + ex.getMessage());
                ex.printStackTrace();
            }
        }).start();
    }

    private static void uploadMusicInternal(MusicLibrary.SavedMusic music, UploadListener listener) throws Exception {
        if (music.xmlFile == null || !music.xmlFile.exists()) {
            throw new FileNotFoundException("XML não encontrado");
        }

        String aliancaPath = "Alian%C3%A7a%20Vocal";
        String authorPath = encodePathSegment(music.author);
        String musicPath = encodePathSegment(music.name.split(" - ")[1].trim());
        String remoteMusicPath = aliancaPath + "/" + authorPath + "/" + musicPath;

        listener.onProgress("Verificando mudanças...", 0, 0);

        Map<String, FileStatus> fileStatus = analyzeFiles(music, remoteMusicPath);

        List<FileToUpload> filesToUpload = new ArrayList<>();
        for (Map.Entry<String, FileStatus> entry : fileStatus.entrySet()) {
            if (entry.getValue().hasChanged) {
                filesToUpload.add(new FileToUpload(entry.getKey(), entry.getValue()));
            }
        }

        if (filesToUpload.isEmpty()) {
            listener.onComplete(true, "✓ Nenhuma mudança detectada");
            return;
        }

        listener.onProgress("Preparando " + filesToUpload.size() + " arquivo(s)...", 0, filesToUpload.size());

        int uploaded = 0;
        for (FileToUpload fileToUpload : filesToUpload) {
            uploaded++;
            listener.onProgress("Enviando " + fileToUpload.name + "...", uploaded, filesToUpload.size());

            uploadFile(fileToUpload.file, remoteMusicPath, fileToUpload.status);
            Thread.sleep(RATE_LIMIT_DELAY);
        }

        try {
            updateManifestWithMusic(music, remoteMusicPath, listener);
        } catch (Exception ex) {
            System.err.println("⚠️ Não foi possível atualizar manifest: " + ex.getMessage());
        }

        String summary = String.format("✓ %d arquivo(s) enviado(s)", filesToUpload.size());
        listener.onComplete(true, summary);
    }

    private static Map<String, FileStatus> analyzeFiles(MusicLibrary.SavedMusic music, String remoteMusicPath) throws Exception {
        Map<String, FileStatus> status = new HashMap<>();

        addFileStatus(status, "XML", music.xmlFile, remoteMusicPath);

        if (music.audioFile != null && music.audioFile.exists()) {
            addFileStatus(status, "Áudio", music.audioFile, remoteMusicPath);
        }

        File propsFile = findPropertiesFile(music.folder);
        if (propsFile != null && propsFile.exists()) {
            addFileStatus(status, "Properties", propsFile, remoteMusicPath);
        }

        return status;
    }

    private static void addFileStatus(Map<String, FileStatus> status, String label, File file,
                                      String remoteMusicPath) throws Exception {
        String localHash = calculateFileHash(file);
        String remoteHash = getRemoteFileHash(remoteMusicPath + "/" + file.getName());
        boolean hasChanged = !localHash.equals(remoteHash);

        status.put(label, new FileStatus(file, remoteHash, hasChanged, remoteMusicPath));
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

    private static String getRemoteFileHash(String remotePath) throws Exception {
        try {
            String downloadUrl = getRemoteFileDownloadUrl(remotePath);
            if (downloadUrl == null) {
                return "";
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            URL url = new URL(downloadUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("User-Agent", "Java-GitHubUpload/1.0");

            try (InputStream in = conn.getInputStream()) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    baos.write(buffer, 0, bytesRead);
                }
            } finally {
                conn.disconnect();
            }

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(baos.toByteArray());
            return formatHash(digest.digest());

        } catch (Exception ex) {
            return "";
        }
    }

    private static String getRemoteFileDownloadUrl(String remotePath) throws Exception {
        JSONObject response = getGitHubApiResponse(remotePath);
        return response != null ? response.optString("download_url", null) : null;
    }

    private static String getRemoteFileSha(String remotePath) throws Exception {
        JSONObject response = getGitHubApiResponse(remotePath);
        return response != null ? response.optString("sha", null) : null;
    }

    private static JSONObject getGitHubApiResponse(String remotePath) throws Exception {
        String url = GITHUB_API_URL + "/" + remotePath + "?ref=" + REF;

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("User-Agent", "Java-GitHubUpload/1.0");
        conn.setRequestProperty("Authorization", "token " + GITHUB_TOKEN);

        int responseCode = conn.getResponseCode();

        if (responseCode == 404 || responseCode != 200) {
            conn.disconnect();
            return null;
        }

        try (InputStream in = conn.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return new JSONObject(sb.toString());
        } finally {
            conn.disconnect();
        }
    }

    private static void uploadFile(File file, String remotePath, FileStatus status) throws Exception {
        String fullPath = remotePath + "/" + file.getName();
        byte[] fileContent = Files.readAllBytes(file.toPath());
        String base64Content = Base64.getEncoder().encodeToString(fileContent);

        String action = status.remoteSha != null && !status.remoteSha.isEmpty() ? "Atualizar" : "Adicionar";
        String message = action + ": " + file.getName();

        uploadToGitHub(fullPath, base64Content, status.remoteSha, message);
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
        conn.setRequestProperty("User-Agent", "Java-GitHubUpload/1.0");
        conn.setRequestProperty("Authorization", "token " + GITHUB_TOKEN);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = payload.toString().getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        int responseCode = conn.getResponseCode();

        if (responseCode != 201 && responseCode != 200) {
            String errorMsg = "HTTP " + responseCode;
            InputStream errorStream = conn.getErrorStream();
            if (errorStream != null) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(errorStream, StandardCharsets.UTF_8))) {
                    String line = reader.readLine();
                    if (line != null) {
                        try {
                            JSONObject error = new JSONObject(line);
                            errorMsg += " - " + error.optString("message", "");
                        } catch (Exception ex) {
                            errorMsg += " - " + line;
                        }
                    }
                }
            }
            throw new Exception("Erro ao fazer upload: " + errorMsg);
        }

        conn.disconnect();
    }

    private static void updateManifestWithMusic(MusicLibrary.SavedMusic music,
                                                String remoteMusicPath, UploadListener listener) throws Exception {
        listener.onProgress("Atualizando manifest...", 0, 0);
        ManifestCreator.updateManifestWithSingleMusic(music, remoteMusicPath);
    }

    private static File findPropertiesFile(File folder) {
        File[] files = folder.listFiles((d, n) -> n.endsWith(".properties"));
        return (files != null && files.length > 0) ? files[0] : null;
    }

    private static String encodePathSegment(String segment) throws Exception {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String downloadTextFile(String urlStr) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("User-Agent", "Java-GitHubUpload/1.0");

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

    private static String getCurrentTimestamp() {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").format(new Date());
    }

    private static class FileStatus {
        File file;
        String remoteHash;
        String remoteSha;
        boolean hasChanged;

        FileStatus(File file, String remoteHash, boolean hasChanged, String remoteMusicPath) throws Exception {
            this.file = file;
            this.remoteHash = remoteHash;
            this.hasChanged = hasChanged;

            try {
                this.remoteSha = getRemoteFileSha(remoteMusicPath + "/" + file.getName());
            } catch (Exception ex) {
                this.remoteSha = null;
            }
        }
    }

    private static class FileToUpload {
        String name;
        File file;
        FileStatus status;

        FileToUpload(String name, FileStatus status) {
            this.name = name;
            this.file = status.file;
            this.status = status;
        }
    }
}