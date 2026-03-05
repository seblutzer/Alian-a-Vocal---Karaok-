
package com.karaoke;

import java.io.*;
import java.nio.file.*;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Gerencia a biblioteca local de músicas salvas.
 *
 * Estrutura em disco:
 *   ~/KaraokeMusicas/
 *     NomeDoCoral/
 *       Autor1/
 *         NomeDaMusica/
 *           autor-musica.xml          (arquivo normalizado)
 *           autor-musica.opus/mp3/etc (arquivo normalizado)
 *           config.properties
 *       Autor2/
 *         ...
 *     OutroCoral/
 *       ...
 *
 * Nomenclatura: Exibição = "Autor - Música" | Arquivos = "autor-musica" (normalizado)
 */
public class MusicLibrary {

    public static class SavedMusic {
        public String name;           // "Compositor - Nome da Música"
        public String coral;          // Nome do coral
        public String author;         // Nome do autor/compositor
        public File   folder;
        public File   xmlFile;
        public File   audioFile;
        public double realDurationMin;
        public double offsetSeconds;
        public int    selectedVoice;

        public String audioExtension() {
            if (audioFile == null) return "";
            String n = audioFile.getName();
            int dot = n.lastIndexOf('.');
            return dot >= 0 ? n.substring(dot + 1).toLowerCase() : "";
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static final String ROOT_FOLDER =
            System.getProperty("user.home") + File.separator + "KaraokeMusicas";

    // ── API pública ───────────────────────────────────────────────────────────

    /**
     * Salva uma música na estrutura: ~/Coral/Autor/Música/
     * Os arquivos são salvos com nomes normalizados para compatibilidade com GitHub.
     *
     * @param fullName    Nome no formato "Autor - Música" (detecta automaticamente)
     * @param coral       Nome do coral/grupo
     * @param xmlSource   XML original — será COPIADO
     * @param audioSource Áudio original — será COPIADO; null = sem áudio
     * @param realDurationMin Duração em minutos
     * @param offsetSeconds Ajuste de sincronização
     * @param selectedVoice Índice da voz selecionada
     */
    public static SavedMusic save(String fullName,
                                  String coral,
                                  File xmlSource,
                                  File audioSource,
                                  double realDurationMin,
                                  double offsetSeconds,
                                  int selectedVoice) throws IOException {

        if (xmlSource == null || !xmlSource.exists())
            throw new FileNotFoundException("XML não encontrado: " + xmlSource);
        if (coral == null || coral.trim().isEmpty())
            throw new IllegalArgumentException("Nome do coral não pode estar vazio.");

        // Extrai autor e nome da música
        String[] parts = parseAuthorAndMusic(fullName);
        String author = parts[0];
        String musicName = parts[1];

        File folder = musicFolder(coral, author, musicName);
        folder.mkdirs();

        // Cria nome normalizado para os arquivos
        String normalizedFileName = normalizeForFilename(author, musicName);

        // Copia XML
        Files.copy(xmlSource.toPath(),
                new File(folder, normalizedFileName + ".xml").toPath(),
                StandardCopyOption.REPLACE_EXISTING);

        // Copia áudio
        String audioExt = "";
        if (audioSource != null && audioSource.exists()) {
            audioExt = extractExtension(audioSource.getName());
            File audioDest = new File(folder, normalizedFileName + "." + audioExt);
            cleanOldAudioFiles(folder, audioDest.getName());
            Files.copy(audioSource.toPath(), audioDest.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }

        saveConfig(folder, author, musicName, realDurationMin, offsetSeconds,
                selectedVoice, audioExt);

        return buildEntry(coral, author, musicName, folder);
    }

    /**
     * Lista todos os corais cadastrados.
     */
    public static List<String> listAllCorals() {
        List<String> corals = new ArrayList<>();
        File root = new File(ROOT_FOLDER);
        if (!root.exists()) return corals;

        File[] coralDirs = root.listFiles(File::isDirectory);
        if (coralDirs == null) return corals;

        for (File d : coralDirs) {
            if (hasAnyMusic(d)) {
                corals.add(d.getName());
            }
        }

        Collections.sort(corals, String.CASE_INSENSITIVE_ORDER);
        return corals;
    }

    /**
     * Lista todas as músicas de um coral específico.
     */
    public static List<SavedMusic> listMusicsByCoral(String coral) {
        List<SavedMusic> list = new ArrayList<>();

        if (coral == null || coral.trim().isEmpty())
            return list;

        File coralDir = new File(ROOT_FOLDER + File.separator + safeName(coral));
        if (!coralDir.exists()) return list;

        File[] authorDirs = coralDir.listFiles(File::isDirectory);
        if (authorDirs == null) return list;

        Arrays.sort(authorDirs, Comparator.comparing(File::getName,
                String.CASE_INSENSITIVE_ORDER));

        for (File authorDir : authorDirs) {
            File[] musicDirs = authorDir.listFiles(File::isDirectory);
            if (musicDirs == null) continue;

            Arrays.sort(musicDirs, Comparator.comparing(File::getName,
                    String.CASE_INSENSITIVE_ORDER));

            for (File musicDir : musicDirs) {
                // Tenta encontrar o XML com nome normalizado ou antigo
                File xmlFile = findXmlFile(musicDir);
                if (xmlFile != null && xmlFile.exists()) {
                    String author = authorDir.getName();
                    String musicName = musicDir.getName();
                    list.add(buildEntry(coral, author, musicName, musicDir));
                }
            }
        }

        return list;
    }

    /**
     * Lista TODAS as músicas (para compatibilidade ou backup).
     */
    public static List<SavedMusic> listAll() {
        List<SavedMusic> all = new ArrayList<>();
        for (String coral : listAllCorals()) {
            all.addAll(listMusicsByCoral(coral));
        }
        return all;
    }

    /**
     * Atualiza metadados sem recopiar arquivos.
     */
    public static SavedMusic updateMetadata(SavedMusic music,
                                            double realDurationMin,
                                            double offsetSeconds,
                                            int selectedVoice) throws IOException {
        String ext = music.audioFile != null ? extractExtension(music.audioFile.getName()) : "";
        saveConfig(music.folder, music.author, music.name.split(" - ")[1],
                realDurationMin, offsetSeconds, selectedVoice, ext);
        music.realDurationMin = realDurationMin;
        music.offsetSeconds   = offsetSeconds;
        music.selectedVoice   = selectedVoice;
        return music;
    }

    /**
     * Renomeia uma música.
     */
    public static SavedMusic rename(SavedMusic music, String newFullName) throws IOException {
        String[] newParts = parseAuthorAndMusic(newFullName);
        String newAuthor = newParts[0];
        String newMusicName = newParts[1];

        // Se mudou de autor, cria pasta nova
        if (!newAuthor.equals(music.author)) {
            File newFolder = musicFolder(music.coral, newAuthor, newMusicName);
            if (newFolder.exists() && !newFolder.equals(music.folder)) {
                throw new IOException("Já existe uma música com esse autor/nome.");
            }
            newFolder.getParentFile().mkdirs();
            if (!music.folder.renameTo(newFolder)) {
                throw new IOException("Não foi possível renomear a pasta.");
            }
            music.folder = newFolder;
            music.author = newAuthor;
        } else if (!newMusicName.equals(music.name.split(" - ")[1])) {
            // Mesma pasta, só renomeia
            File newFolder = new File(music.folder.getParent(), newMusicName);
            if (newFolder.exists() && !newFolder.equals(music.folder)) {
                throw new IOException("Já existe uma música com esse nome.");
            }
            if (!music.folder.renameTo(newFolder)) {
                throw new IOException("Não foi possível renomear a pasta.");
            }
            music.folder = newFolder;
        }

        String fullName = newAuthor + " - " + newMusicName;
        String normalizedFileName = normalizeForFilename(newAuthor, newMusicName);
        File cfg = new File(music.folder, normalizedFileName + ".properties");

        Properties props = loadProperties(cfg);
        props.setProperty("author", newAuthor);
        props.setProperty("musicName", newMusicName);
        storeProperties(props, cfg, "Karaoke config — " + fullName);

        return music;
    }

    /**
     * Exclui uma música.
     */
    public static void delete(SavedMusic music) throws IOException {
        deleteFolder(music.folder);

        // Limpa pastas vazias
        File authorDir = music.folder.getParentFile();
        if (authorDir.listFiles() != null && authorDir.listFiles().length == 0) {
            authorDir.delete();
        }
        File coralDir = authorDir.getParentFile();
        if (coralDir != null && coralDir.listFiles() != null && coralDir.listFiles().length == 0) {
            coralDir.delete();
        }
    }

    // ── Helpers privados ───────────────────────────────────────────────────────

    /**
     * Normaliza um nome para uso em nome de arquivo.
     * Formato: "autor-musica" (minúsculas, sem acentos, sem espaços)
     */
    private static String normalizeForFilename(String author, String musicName) {
        String combined = author.trim() + "-" + musicName.trim();

        // Remove acentos usando NFD
        String normalized = Normalizer.normalize(combined, Normalizer.Form.NFD);
        normalized = normalized.replaceAll("\\p{M}", ""); // Remove diacríticos

        // Converte para minúsculas
        normalized = normalized.toLowerCase();

        // Substitui espaços por _
        normalized = normalized.replaceAll("\\s+", "_");

        // Remove caracteres inválidos para nomes de arquivo
        normalized = normalized.replaceAll("[\\\\/:*?\"<>|]", "");

        return normalized;
    }

    /**
     * Encontra o arquivo XML na pasta (compatível com nomes antigos e novos)
     */
    private static File findXmlFile(File folder) {
        File[] files = folder.listFiles((d, n) -> n.endsWith(".xml"));
        if (files != null && files.length > 0) {
            return files[0];
        }
        return null;
    }

    /**
     * Encontra o arquivo de áudio na pasta (compatível com nomes antigos e novos)
     */
    private static File findAudioFile(File folder) {
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

    private static String[] parseAuthorAndMusic(String fullName) {
        // Suporta: "Autor - Música", "Autor – Música", "Autor — Música"
        fullName = fullName.trim();
        String[] parts = fullName.split("[\\-–—]+", 2);

        if (parts.length == 2) {
            return new String[]{parts[0].trim(), parts[1].trim()};
        } else {
            // Se não encontrar separador, assume tudo como música, autor = "Diversos"
            return new String[]{"Diversos", fullName};
        }
    }

    private static SavedMusic buildEntry(String coral, String author, String musicName, File folder) {
        SavedMusic m = new SavedMusic();
        m.coral   = coral;
        m.author  = author;
        m.name    = author + " - " + musicName;
        m.folder  = folder;
        m.xmlFile = findXmlFile(folder);

        File cfg = findConfigFile(folder);
        String audioExt = "";

        // ✅ Valores padrão
        m.realDurationMin = 0.0;
        m.offsetSeconds   = 0.0;
        m.selectedVoice   = 0;

        if (cfg != null && cfg.exists()) {
            try {
                Properties props = loadProperties(cfg);

                audioExt = props.getProperty("audioExtension", "");

                // ✅ Carregar realDurationMin (aceita ponto E vírgula)
                try {
                    String durStr = props.getProperty("realDurationMin", "0").trim();
                    if (!durStr.isEmpty()) {
                        durStr = durStr.replace(",", "."); // ✅ Converte vírgula para ponto
                        m.realDurationMin = Double.parseDouble(durStr);
                    }
                } catch (NumberFormatException ex) {
                    System.err.println("⚠️  Erro ao carregar realDurationMin: " + ex.getMessage());
                    m.realDurationMin = 0.0;
                }

                // ✅ Carregar offsetSeconds (aceita ponto E vírgula)
                try {
                    String offsetStr = props.getProperty("offsetSeconds", "0").trim();
                    if (!offsetStr.isEmpty()) {
                        offsetStr = offsetStr.replace(",", "."); // ✅ Converte vírgula para ponto
                        m.offsetSeconds = Double.parseDouble(offsetStr);
                    }
                } catch (NumberFormatException ex) {
                    System.err.println("⚠️  Erro ao carregar offsetSeconds: " + ex.getMessage());
                    m.offsetSeconds = 0.0;
                }

                // ✅ Carregar selectedVoice
                try {
                    String voiceStr = props.getProperty("selectedVoice", "0").trim();
                    if (!voiceStr.isEmpty()) {
                        m.selectedVoice = Integer.parseInt(voiceStr);
                    }
                } catch (NumberFormatException ex) {
                    System.err.println("⚠️  Erro ao carregar selectedVoice: " + ex.getMessage());
                    m.selectedVoice = 0;
                }

            } catch (Exception ex) {
                System.err.println("⚠️  Erro ao carregar properties: " + ex.getMessage());
            }
        } else {
            System.err.println("⚠️  Arquivo properties não encontrado para: " + m.name);
        }

        // Procurar arquivo de áudio
        if (!audioExt.isEmpty()) {
            File audio = findAudioFile(folder);
            m.audioFile = audio != null && audio.exists() ? audio : null;
        }

        return m;
    }

    private static File findConfigFile(File folder) {
        File[] files = folder.listFiles((d, n) -> n.endsWith(".properties"));
        if (files != null && files.length > 0) {
            return files[0];
        }
        return null;
    }

    private static void saveConfig(File folder, String author, String musicName,
                                   double realDurationMin, double offsetSeconds,
                                   int selectedVoice, String audioExt) throws IOException {
        Properties props = new Properties();
        props.setProperty("author", author);
        props.setProperty("musicName", musicName);
        props.setProperty("audioExtension", audioExt);
        props.setProperty("realDurationMin", String.format(java.util.Locale.US, "%.4f", realDurationMin));
        props.setProperty("offsetSeconds", String.format(java.util.Locale.US, "%.2f", offsetSeconds));
        props.setProperty("selectedVoice", String.valueOf(selectedVoice));

        String normalizedFileName = normalizeForFilename(author, musicName);
        File configFile = new File(folder, normalizedFileName + ".properties");

        storeProperties(props, configFile,
                "Karaoke config — " + author + " - " + musicName);
    }

    private static boolean hasAnyMusic(File coralDir) {
        File[] authorDirs = coralDir.listFiles(File::isDirectory);
        if (authorDirs == null) return false;
        for (File authorDir : authorDirs) {
            File[] musicDirs = authorDir.listFiles(File::isDirectory);
            if (musicDirs != null) {
                for (File musicDir : musicDirs) {
                    if (findXmlFile(musicDir) != null) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static void cleanOldAudioFiles(File folder, String keepFileName) {
        File[] files = folder.listFiles();
        if (files == null) return;
        for (File f : files) {
            String n = f.getName();
            String lower = n.toLowerCase();
            if ((lower.endsWith(".opus") || lower.endsWith(".mp3") ||
                    lower.endsWith(".wav") || lower.endsWith(".ogg") ||
                    lower.endsWith(".flac") || lower.endsWith(".aac"))
                    && !n.equals(keepFileName)) {
                f.delete();
            }
        }
    }

    private static String extractExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return (dot >= 0 && dot < filename.length() - 1)
                ? filename.substring(dot + 1).toLowerCase()
                : "bin";
    }

    private static File musicFolder(String coral, String author, String musicName) {
        String safeCoralName = safeName(coral);
        String safeAuthor = safeName(author);
        String safeMusic = safeName(musicName);

        return new File(ROOT_FOLDER
                + File.separator + safeCoralName
                + File.separator + safeAuthor
                + File.separator + safeMusic);
    }

    private static String safeName(String name) {
        return name.trim()
                .replaceAll("[\\\\/:*?\"<>|]", "_")
                .replaceAll("\\s+", " ");
    }

    private static Properties loadProperties(File file) throws IOException {
        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(file)) {
            props.load(fis);
        }
        return props;
    }

    private static void storeProperties(Properties props, File file,
                                        String comment) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            props.store(fos, comment);
        }
    }

    private static void deleteFolder(File folder) throws IOException {
        if (!folder.exists()) return;
        File[] files = folder.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) deleteFolder(f);
                else f.delete();
            }
        }
        folder.delete();
    }
}