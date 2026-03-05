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
 * Gerencia upload inteligente de músicas para o repositório GitHub.
 *
 * Recursos:
 * - Compara hash SHA-256 dos arquivos locais vs remotos
 * - Só faz upload dos arquivos que mudaram
 * - Upload de XML, áudio E configuração (.properties)
 * - Criação automática de estrutura de pastas
 * - Versionamento com SHA do GitHub
 */
public class GitHubUploadManager {

    private static final String GITHUB_API_URL = GitHubConfigManager.getApiUrl();
    private static final String GITHUB_REPO = GitHubConfigManager.getRepo();
    private static final String GITHUB_TOKEN = GitHubConfigManager.getToken();
    private static final String REF = GitHubConfigManager.getRef();
    private static final long RATE_LIMIT_DELAY = GitHubConfigManager.getUploadRateLimit();

    public interface UploadListener {
        void onProgress(String message, int current, int total);
        void onError(String error);
        void onComplete(boolean success, String message);
    }

    /**
     * Inicia upload inteligente de uma música para GitHub
     * Verifica mudanças antes de fazer upload
     */
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

    // ── Métodos privados ──────────────────────────────────────────────────────

    private static void uploadMusicInternal(MusicLibrary.SavedMusic music, UploadListener listener) throws Exception {
        if (music.xmlFile == null || !music.xmlFile.exists()) {
            throw new FileNotFoundException("XML não encontrado");
        }

        // Estrutura: Aliança Vocal/Autor/MúsicaNome/
        String aliancaPath = "Alian%C3%A7a%20Vocal";
        String authorPath = encodePathSegment(music.author);
        String musicPath = encodePathSegment(music.name.split(" - ")[1]);
        String remoteMusicPath = aliancaPath + "/" + authorPath + "/" + musicPath;

        listener.onProgress("Verificando mudanças...", 0, 0);

        // ✅ NOVO: Analisar quais arquivos mudaram
        Map<String, FileStatus> fileStatus = analyzeFiles(music, remoteMusicPath);

        List<FileToUpload> filesToUpload = new ArrayList<>();
        for (Map.Entry<String, FileStatus> entry : fileStatus.entrySet()) {
            if (entry.getValue().hasChanged) {
                filesToUpload.add(new FileToUpload(entry.getKey(), entry.getValue()));
            }
        }

        if (filesToUpload.isEmpty()) {
            listener.onComplete(true, "✓ Nenhuma mudança detectada - arquivo já está atualizado!");
            return;
        }

        listener.onProgress("Preparando " + filesToUpload.size() + " arquivo(s)...", 0, filesToUpload.size());

        // ✅ Fazer upload apenas dos arquivos que mudaram
        int uploaded = 0;
        for (FileToUpload fileToUpload : filesToUpload) {
            uploaded++;
            listener.onProgress("Enviando " + fileToUpload.name + "...", uploaded, filesToUpload.size());

            uploadFile(fileToUpload.file, remoteMusicPath, fileToUpload.status);
            Thread.sleep(RATE_LIMIT_DELAY);
        }

        String summary = String.format("✓ %d arquivo(s) enviado(s) com sucesso!", filesToUpload.size());
        listener.onComplete(true, summary);
    }

    /**
     * ✅ NOVO: Analisa quais arquivos mudaram comparando hashes
     */
    private static Map<String, FileStatus> analyzeFiles(MusicLibrary.SavedMusic music, String remoteMusicPath) throws Exception {
        Map<String, FileStatus> status = new HashMap<>();

        // Arquivos locais
        File xmlFile = music.xmlFile;
        File audioFile = music.audioFile;
        File propsFile = findPropertiesFile(music.folder);

        // Calcula hashes locais
        String xmlLocalHash = calculateFileHash(xmlFile);
        String audioLocalHash = audioFile != null ? calculateFileHash(audioFile) : null;
        String propsLocalHash = propsFile != null ? calculateFileHash(propsFile) : null;

        // ✅ Verificar XML
        String xmlRemoteHash = getRemoteFileHash(remoteMusicPath + "/" + xmlFile.getName());
        boolean xmlChanged = !xmlLocalHash.equals(xmlRemoteHash);
        status.put("XML", new FileStatus(xmlFile, xmlRemoteHash, xmlChanged, remoteMusicPath));

        // ✅ Verificar Áudio
        if (audioFile != null && audioFile.exists()) {
            String audioRemoteHash = getRemoteFileHash(remoteMusicPath + "/" + audioFile.getName());
            boolean audioChanged = !audioLocalHash.equals(audioRemoteHash);
            status.put("Áudio", new FileStatus(audioFile, audioRemoteHash, audioChanged, remoteMusicPath));
        }

        // ✅ Verificar Properties
        if (propsFile != null && propsFile.exists()) {
            String propsRemoteHash = getRemoteFileHash(remoteMusicPath + "/" + propsFile.getName());
            boolean propsChanged = !propsLocalHash.equals(propsRemoteHash);
            status.put("Properties", new FileStatus(propsFile, propsRemoteHash, propsChanged, remoteMusicPath));
        }

        // Log das mudanças (✅ CORRIGIDO: Safe substring)
        System.out.println("\n=== ANÁLISE DE MUDANÇAS ===");
        for (Map.Entry<String, FileStatus> entry : status.entrySet()) {
            String indicator = entry.getValue().hasChanged ? "⭕ MUDOU" : "✓ OK";
            String localHashShort = safeSubstring(entry.getValue().localHash, 0, 8);
            String remoteHashShort = entry.getValue().remoteHash != null && !entry.getValue().remoteHash.isEmpty()
                    ? safeSubstring(entry.getValue().remoteHash, 0, 8)
                    : "NOVO";
            System.out.println(indicator + " - " + entry.getKey() + " (local: " + localHashShort + " | remoto: " + remoteHashShort + ")");
        }
        System.out.println();

        return status;
    }

    /**
     * ✅ NOVO: Substring seguro que não lança exceção
     */
    private static String safeSubstring(String str, int start, int end) {
        if (str == null || str.isEmpty()) {
            return "VAZIO";
        }
        int actualEnd = Math.min(end, str.length());
        return str.substring(start, actualEnd);
    }

    /**
     * ✅ NOVO: Calcula SHA-256 de um arquivo local
     */
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

        byte[] hashBytes = digest.digest();
        StringBuilder hashString = new StringBuilder();
        for (byte b : hashBytes) {
            hashString.append(String.format("%02x", b));
        }
        return hashString.toString();
    }

    /**
     * ✅ NOVO: Obtém hash do arquivo remoto
     * Faz download do arquivo remoto e calcula hash
     * Retorna vazio se não existir
     */
    private static String getRemoteFileHash(String remotePath) throws Exception {
        try {
            String downloadUrl = getRemoteFileDownloadUrl(remotePath);
            if (downloadUrl == null) {
                return ""; // Arquivo não existe no remoto
            }

            // Faz download temporário e calcula hash
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

            byte[] fileContent = baos.toByteArray();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(fileContent);

            byte[] hashBytes = digest.digest();
            StringBuilder hashString = new StringBuilder();
            for (byte b : hashBytes) {
                hashString.append(String.format("%02x", b));
            }
            return hashString.toString();

        } catch (Exception ex) {
            // Se não conseguir baixar, considera como "não existe"
            return "";
        }
    }

    /**
     * ✅ NOVO: Obtém a URL de download de um arquivo remoto
     */
    private static String getRemoteFileDownloadUrl(String remotePath) throws Exception {
        String url = GITHUB_API_URL + "/" + remotePath + "?ref=" + REF;

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("User-Agent", "Java-GitHubUpload/1.0");
        conn.setRequestProperty("Authorization", "token " + GITHUB_TOKEN);

        int responseCode = conn.getResponseCode();

        if (responseCode == 404) {
            return null; // Arquivo não existe
        }

        if (responseCode != 200) {
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
            return obj.optString("download_url", null);
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Faz upload de um arquivo individual para GitHub
     */
    private static void uploadFile(File file, String remotePath, FileStatus status) throws Exception {
        String fileName = file.getName();
        String fullPath = remotePath + "/" + fileName;

        // Lê arquivo e codifica em Base64
        byte[] fileContent = Files.readAllBytes(file.toPath());
        String base64Content = Base64.getEncoder().encodeToString(fileContent);

        // Se arquivo já existe, usa o SHA anterior para update
        String message = status.remoteSha != null && !status.remoteSha.isEmpty()
                ? "Atualizar: " + fileName + " (mudança detectada)"
                : "Adicionar: " + fileName + " (novo)";

        uploadToGitHub(fullPath, base64Content, status.remoteSha, message);
    }

    /**
     * Encontra o arquivo .properties na pasta da música
     */
    private static File findPropertiesFile(File folder) {
        File[] files = folder.listFiles((d, n) -> n.endsWith(".properties"));
        if (files != null && files.length > 0) {
            return files[0];
        }
        return null;
    }

    /**
     * Obtém o SHA do GitHub para um arquivo (para fazer update)
     */
    private static String getRemoteFileSha(String remotePath) throws Exception {
        String url = GITHUB_API_URL + "/" + remotePath + "?ref=" + REF;

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("User-Agent", "Java-GitHubUpload/1.0");
        conn.setRequestProperty("Authorization", "token " + GITHUB_TOKEN);

        int responseCode = conn.getResponseCode();

        if (responseCode == 404) {
            return null;
        }

        if (responseCode != 200) {
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

    /**
     * Faz upload/atualização de um arquivo no GitHub
     */
    private static void uploadToGitHub(String remotePath, String base64Content,
                                       String existingSha, String message) throws Exception {
        String url = GITHUB_API_URL + "/" + remotePath;

        JSONObject payload = new JSONObject();
        payload.put("message", message);
        payload.put("content", base64Content);
        payload.put("branch", REF);

        // ✅ CORRIGIDO: Só adiciona SHA se não for null E não for vazio
        if (existingSha != null && !existingSha.trim().isEmpty()) {
            payload.put("sha", existingSha);
            System.out.println("  ✓ Atualizando com SHA: " + existingSha.substring(0, 8));
        } else {
            System.out.println("  ✓ Criando novo arquivo (sem SHA)");
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
            InputStream errorStream = conn.getErrorStream();
            String errorMsg = "HTTP " + responseCode;
            if (errorStream != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(errorStream, StandardCharsets.UTF_8))) {
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

    /**
     * Codifica um segmento de path para URL (compatível com GitHub)
     */
    private static String encodePathSegment(String segment) throws Exception {
        String encoded = URLEncoder.encode(segment, StandardCharsets.UTF_8);
        return encoded.replace("+", "%20");
    }

    // ── Classes internas ──────────────────────────────────────────────────────

    /**
     * Status de um arquivo (se mudou ou não)
     */
    private static class FileStatus {
        File file;
        String remoteHash;
        String remoteSha;
        String localHash;
        boolean hasChanged;

        FileStatus(File file, String remoteHash, boolean hasChanged, String remoteMusicPath) throws Exception {
            this.file = file;
            this.remoteHash = remoteHash;
            this.hasChanged = hasChanged;
            this.localHash = calculateFileHash(file);
            this.remoteSha = null; // ✅ Inicializa como null

            // ✅ CORRIGIDO: Sempre tenta obter SHA, mesmo que hash esteja vazio
            try {
                this.remoteSha = getRemoteFileSha(remoteMusicPath + "/" + file.getName());
            } catch (Exception ex) {
                System.err.println("⚠️  Não foi possível obter SHA remoto: " + ex.getMessage());
                this.remoteSha = null;
            }
        }
    }

    /**
     * Arquivo pronto para upload
     */
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
