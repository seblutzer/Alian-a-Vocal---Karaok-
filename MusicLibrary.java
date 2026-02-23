
package com.karaoke;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Gerencia a biblioteca local de músicas salvas.
 *
 * Estrutura em disco:
 *   ~/KaraokeMusicas/
 *     NomeDaMusica/
 *       musica.xml             ← cópia do XML original
 *       musica.<ext>           ← cópia do áudio (mp3, opus, wav, flac…)
 *       config.properties      ← duração real + offset + voz + extensão do áudio
 *
 * Mudanças em relação à versão anterior:
 *  • mp3File → audioFile  (suporta qualquer extensão)
 *  • audioExtension salva no config.properties para leitura correta
 *  • save() aceita audioSource com qualquer extensão; copia como musica.<ext>
 */
public class MusicLibrary {

    // ── Dados de uma música salva ─────────────────────────────────────────────
    public static class SavedMusic {
        public String name;
        public File   folder;
        public File   xmlFile;
        public File   audioFile;          // null se não houver áudio
        public double realDurationMin;
        public double offsetSeconds;
        public int    selectedVoice;

        /** Extensão do arquivo de áudio, ex: "mp3", "opus". Vazio se sem áudio. */
        public String audioExtension() {
            if (audioFile == null) return "";
            String n = audioFile.getName();
            int dot = n.lastIndexOf('.');
            return dot >= 0 ? n.substring(dot + 1).toLowerCase() : "";
        }

        @Override public String toString() { return name; }
    }

    private static final String ROOT_FOLDER =
            System.getProperty("user.home") + File.separator + "KaraokeMusicas";

    // ── API pública ───────────────────────────────────────────────────────────

    /**
     * Salva (ou atualiza) uma música copiando os arquivos para a biblioteca.
     *
     * @param name            Nome exibido na biblioteca
     * @param xmlSource       XML original — será COPIADO
     * @param audioSource     Áudio original (mp3, opus, wav…) — será COPIADO; null = sem áudio
     * @param realDurationMin Duração em minutos (0 = usar do XML)
     * @param offsetSeconds   Ajuste de sincronização em segundos
     * @param selectedVoice   Índice da voz selecionada
     */
    public static SavedMusic save(String name,
                                  File xmlSource,
                                  File audioSource,
                                  double realDurationMin,
                                  double offsetSeconds,
                                  int selectedVoice) throws IOException {

        if (xmlSource == null || !xmlSource.exists())
            throw new FileNotFoundException("XML não encontrado: " + xmlSource);

        File folder = musicFolder(name);
        folder.mkdirs();

        // ── Copia XML ──────────────────────────────────────────────────────────
        Files.copy(xmlSource.toPath(),
                new File(folder, "musica.xml").toPath(),
                StandardCopyOption.REPLACE_EXISTING);

        // ── Copia áudio (qualquer extensão) ───────────────────────────────────
        String audioExt = "";
        if (audioSource != null && audioSource.exists()) {
            audioExt = extractExtension(audioSource.getName());
            File audioDest = new File(folder, "musica." + audioExt);

            // Remove áudio anterior com extensão diferente (ex: mp3 → opus)
            cleanOldAudioFiles(folder, audioDest.getName());

            Files.copy(audioSource.toPath(), audioDest.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }

        saveConfig(folder, name, realDurationMin, offsetSeconds, selectedVoice, audioExt);
        return buildEntry(name, folder);
    }

    /**
     * Atualiza apenas os metadados sem recopiar arquivos.
     */
    public static SavedMusic updateMetadata(SavedMusic music,
                                            double realDurationMin,
                                            double offsetSeconds,
                                            int selectedVoice) throws IOException {
        String ext = music.audioFile != null ? extractExtension(music.audioFile.getName()) : "";
        saveConfig(music.folder, music.name, realDurationMin, offsetSeconds, selectedVoice, ext);
        music.realDurationMin = realDurationMin;
        music.offsetSeconds   = offsetSeconds;
        music.selectedVoice   = selectedVoice;
        return music;
    }

    /**
     * Renomeia uma música (renomeia a pasta no disco).
     */
    public static SavedMusic rename(SavedMusic music, String newName) throws IOException {
        File newFolder = musicFolder(newName);
        if (newFolder.exists())
            throw new IOException("Já existe uma música com o nome: " + newName);
        if (!music.folder.renameTo(newFolder))
            throw new IOException("Não foi possível renomear a pasta.");

        File cfg = new File(newFolder, "config.properties");
        Properties props = loadProperties(cfg);
        props.setProperty("name", newName);
        storeProperties(props, cfg, "Karaoke config — " + newName);

        return buildEntry(newName, newFolder);
    }

    /**
     * Lista todas as músicas salvas em ordem alfabética.
     */
    public static List<SavedMusic> listAll() {
        List<SavedMusic> list = new ArrayList<>();
        File root = new File(ROOT_FOLDER);
        if (!root.exists()) return list;

        File[] folders = root.listFiles(File::isDirectory);
        if (folders == null) return list;
        Arrays.sort(folders, Comparator.comparing(File::getName,
                String.CASE_INSENSITIVE_ORDER));

        for (File f : folders)
            if (new File(f, "musica.xml").exists())
                list.add(buildEntry(f.getName(), f));

        return list;
    }

    /**
     * Exclui permanentemente uma música da biblioteca.
     */
    public static void delete(SavedMusic music) throws IOException {
        deleteFolder(music.folder);
    }

    // ── Interno ───────────────────────────────────────────────────────────────

    private static SavedMusic buildEntry(String name, File folder) {
        SavedMusic m = new SavedMusic();
        m.name    = name;
        m.folder  = folder;
        m.xmlFile = new File(folder, "musica.xml");

        File cfg = new File(folder, "config.properties");
        String audioExt = "";
        if (cfg.exists()) {
            try {
                Properties props  = loadProperties(cfg);
                audioExt          = props.getProperty("audioExtension", "");
                m.realDurationMin = Double.parseDouble(
                        props.getProperty("realDurationMin", "0"));
                m.offsetSeconds   = Double.parseDouble(
                        props.getProperty("offsetSeconds",   "0"));
                m.selectedVoice   = Integer.parseInt(
                        props.getProperty("selectedVoice",   "0"));
            } catch (Exception ignored) {}
        }

        // Tenta localizar o arquivo de áudio
        if (!audioExt.isEmpty()) {
            File audio = new File(folder, "musica." + audioExt);
            m.audioFile = audio.exists() ? audio : null;
        } else {
            // Compatibilidade retroativa: procura musica.mp3
            File legacy = new File(folder, "musica.mp3");
            m.audioFile = legacy.exists() ? legacy : null;
        }

        return m;
    }

    private static void saveConfig(File folder, String name,
                                   double realDurationMin, double offsetSeconds,
                                   int selectedVoice, String audioExt) throws IOException {
        Properties props = new Properties();
        props.setProperty("name",            name);
        props.setProperty("audioExtension",  audioExt);
        props.setProperty("realDurationMin", String.valueOf(realDurationMin));
        props.setProperty("offsetSeconds",   String.valueOf(offsetSeconds));
        props.setProperty("selectedVoice",   String.valueOf(selectedVoice));
        storeProperties(props, new File(folder, "config.properties"),
                "Karaoke config — " + name);
    }

    /** Remove arquivos musica.* com extensões diferentes do novo (ex: mp3 ao salvar opus). */
    private static void cleanOldAudioFiles(File folder, String keepFileName) {
        File[] files = folder.listFiles();
        if (files == null) return;
        for (File f : files) {
            String n = f.getName();
            if (n.startsWith("musica.") && !n.equals("musica.xml")
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

    private static Properties loadProperties(File file) throws IOException {
        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(file)) { props.load(fis); }
        return props;
    }

    private static void storeProperties(Properties props, File file,
                                        String comment) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            props.store(fos, comment);
        }
    }

    private static File musicFolder(String name) {
        String safe = name.trim()
                .replaceAll("[\\\\/:*?\"<>|]", "_")
                .replaceAll("\\s+", " ");
        return new File(ROOT_FOLDER + File.separator + safe);
    }

    private static void deleteFolder(File folder) throws IOException {
        if (!folder.exists()) return;
        File[] files = folder.listFiles();
        if (files != null)
            for (File f : files) {
                if (f.isDirectory()) deleteFolder(f);
                else f.delete();
            }
        folder.delete();
    }
}
